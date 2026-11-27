//================================================================================================================================
//
// Copyright (c) 2015-2025 VisionStar Information Technology (Shanghai) Co., Ltd. All Rights Reserved.
// EasyAR is the registered trademark or trademark of VisionStar Information Technology (Shanghai) Co., Ltd in China
// and other countries for the augmented reality technology developed by VisionStar Information Technology (Shanghai) Co., Ltd.
//
//================================================================================================================================

package cn.easyar.samples.helloar.ar

import cn.easyar.samples.helloar.ui.*
import cn.easyar.samples.helloar.puzzle.*
import cn.easyar.samples.helloar.model.*
import cn.easyar.samples.helloar.render.*
import cn.easyar.samples.helloar.data.*
import cn.easyar.samples.helloar.util.*

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log

import cn.easyar.*

class HelloAR(context: Context, initialSettings: PuzzleSettings) {
    private val assets: AssetManager = context.assets
    private val history = HistoryStore.forContext(context)
    private val photoSaver = PhotoSaver(context.applicationContext, history)
    private val progress = ProgressStore.forContext(context)
    private val records = RecordStore.forContext(context)
    private val io: ExecutorService = Executors.newSingleThreadExecutor()   // запись истории и фото вне GL-потока

    // настройки пазла; можно менять из любого потока (применяются со следующего кадра)
    @Volatile
    var settings: PuzzleSettings = initialSettings

    companion object {
        private const val TILE_GAP = 0.03f         // зазор между плитками (доля размера плитки)
        private const val SELECTED_SCALE = 0.8f    // во сколько раз уменьшается выбранная плитка
        private const val BACKDROP_SCALE = 1.04f   // тёмная подложка чуть больше картинки
        private const val REWARD_POP_SECONDS = 0.7f   // за сколько фигурка «вырастает» после сборки пазла
        private const val REWARD_SWAY_DEG = 15f          // покачивание фигурки вокруг вертикали, градусы
        private const val REWARD_BOB = 0.015f         // амплитуда покачивания в ширинах картинки
    }

    private var scheduler: DelayedCallbackScheduler? = null
    private var camera: CameraDevice? = null
    private val trackers: ArrayList<ImageTracker>
    private var bgRenderer: BGRenderer? = null
    private var tileRenderer: TileRenderer? = null
    private val assetPaths = HashMap<String, String>()   // имя цели -> путь к картинке в assets
    private val failedTextures = HashSet<String>()
    private var modelRenderer: ModelRenderer? = null
    private var puzzleDefs: Map<String, PuzzleDef> = emptyMap()
    private val modelState = HashMap<String, Int>()      // GL-поток: нет записи - не запрашивали, 1 - грузится, 2 - в видеопамяти, -1 - модели нет или она не читается
    private val loadedModels = ConcurrentHashMap<String, LoadedModel>()   // разобранные в фоне модели, ждут загрузки в видеопамять
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()   // чтение моделей, чтобы не задерживать запись истории и фото
    private val boards = HashMap<String, PuzzleBoard>()  // имя цели -> её пазл
    private val pendingTouch = AtomicReference<FloatArray?>(null)
    private val reshuffleRequest = AtomicBoolean(false)
    private val photoRequest = AtomicReference<((Boolean) -> Unit)?>(null)

    // пока true, поверх плиток показывается целая картинка (подсказка); можно менять из любого потока
    @Volatile
    var hintVisible = false
    private var activeName: String? = null               // пазл, состояние которого показывается в интерфейсе
    @Volatile
    private var lastSeenMs = 0L                          // когда в последний раз была видна любая картинка-цель

    // true, если картинка-цель была в кадре в последние 0,7 с (небольшие пропуски трекера не считаются потерей)
    fun targetVisible(): Boolean = SystemClock.elapsedRealtime() - lastSeenMs < 700

    // состояние активного пазла для интерфейса; обновляется в GL-потоке каждый кадр, читать можно из любого
    @Volatile
    var status: PuzzleStatus? = null
        private set
    private var throttler: InputFrameThrottler? = null
    private var feedbackFrameFork: FeedbackFrameFork? = null
    private var i2OAdapter: InputFrameToOutputFrameAdapter? = null
    private var inputFrameFork: InputFrameFork? = null
    private var join: OutputFrameJoin? = null
    private var oFrameBuffer: OutputFrameBuffer? = null
    private var i2FAdapter: InputFrameToFeedbackFrameAdapter? = null
    private var outputFrameFork: OutputFrameFork? = null
    private var previousInputFrameIndex = -1
    private var imageBytes: ByteArray? = null

    init {
        scheduler = DelayedCallbackScheduler()
        trackers = ArrayList()
    }

    private fun loadFromImage(tracker: ImageTracker, path: String, name: String) {
        val target = ImageTarget.createFromImageFile(path, StorageType.Assets, name, "", "", 1.0f)
        if (target == null) {
            Log.e("HelloAR", "target create failed or key is not correct")
            return
        }
        assetPaths[name] = path
        tracker.loadTarget(target, scheduler!!) { t, status -> Log.i("HelloAR", String.format("load target (%b): %s (%d)", status, t.name(), t.runtimeID())) }
        target.dispose()
    }

    // загружает картинку цели в текстуру при первом использовании (вызывать только в GL-потоке)
    private fun ensureTexture(key: String) {
        val renderer = tileRenderer ?: return
        if (renderer.hasTexture(key) || failedTextures.contains(key)) {
            return
        }
        val path = assetPaths[key]
        if (path == null) {
            Log.e("HelloAR", "no asset path for target: $key")
            failedTextures.add(key)
            return
        }
        try {
            assets.open(path).use { stream ->
                val options = BitmapFactory.Options()
                options.inScaled = false
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                if (bitmap == null) {
                    Log.e("HelloAR", "decode failed: $path")
                    failedTextures.add(key)
                } else {
                    renderer.setTexture(key, bitmap)
                    bitmap.recycle()
                }
            }
        } catch (e: IOException) {
            Log.e("HelloAR", "can not read asset: $path", e)
            failedTextures.add(key)
        }
    }

    // фигурка-награда: модель читается в фоне, в видеопамять попадает в GL-потоке
    private fun requestModel(key: String) {
        val def = puzzleDefs[key]
        if (def == null || def.modelPath == null) {
            modelState[key] = -1
            return
        }
        modelState[key] = 1
        loader.execute {
            try {
                loadedModels[key] = ModelLoader.load(assets, def)
            } catch (e: Throwable) {
                Log.e("HelloAR", "model load failed: $key", e)
            }
        }
    }

    private fun uploadLoadedModels() {
        if (loadedModels.isEmpty()) return
        val renderer = modelRenderer ?: return
        for (key in loadedModels.keys.toList()) {
            val loaded = loadedModels.remove(key) ?: continue
            try {
                renderer.upload(key, loaded)
                modelState[key] = 2
            } catch (e: Exception) {
                Log.e("HelloAR", "model upload failed: $key", e)
                modelState[key] = -1
            } finally {
                for (bitmap in loaded.bitmaps.values) {
                    bitmap.recycle()
                }
            }
        }
    }

    // награда за собранный пазл: фигурка «вырастает» над картинкой, лицом к камере, и слегка покачивается (см. ModelPose)
    private fun drawReward(projectionMatrix: Matrix44F, pose: Matrix44F, key: String, size: Vec2F, board: PuzzleBoard) {
        val renderer = modelRenderer ?: return
        if (!renderer.has(key)) return
        val t = maxOf(SystemClock.elapsedRealtime() - board.finishedAtMs, 0L) / 1000f
        val popped = t >= REWARD_POP_SECONDS
        val pop = if (popped) 1f else {
            val x = t / REWARD_POP_SECONDS - 1f
            1f + 2.70158f * x * x * x + 1.70158f * x * x   // easeOutBack: небольшой «перелёт» и возврат
        }
        val width = size.data[0]
        val lift = if (popped) width * REWARD_BOB * Math.sin((t - REWARD_POP_SECONDS) * 2.0).toFloat() else 0f
        val sway = REWARD_SWAY_DEG * Math.sin(t * 1.5).toFloat()
        val placement = ModelPose.placement(projectionMatrix.data, pose.data, width, pop, renderer.pivotZ(key), sway, lift)
            ?: return
        renderer.render(projectionMatrix, pose, key, placement)
    }

    // касание в нормализованных координатах экрана (NDC: от -1 до 1, y вверх); можно вызывать из любого потока,
    // касание будет обработано в ближайшем кадре
    fun onTouch(nx: Float, ny: Float) {
        pendingTouch.set(floatArrayOf(nx, ny))
    }

    // просит перемешать активный пазл заново (таймер и ходы обнулятся); можно вызывать из любого потока
    fun requestReshuffle() {
        reshuffleRequest.set(true)
    }

    // просит сохранить фото того, что сейчас на экране AR (камера и плитки, без кнопок интерфейса).
    // callback(true/false) вызовется позже, не в UI-потоке
    fun requestPhoto(callback: (Boolean) -> Unit) {
        photoRequest.set(callback)
    }

    // запись о собранном пазле в историю
    private fun recordResult(name: String, board: PuzzleBoard) {
        val entry = HistoryEntry(System.currentTimeMillis(), HistoryEntry.KIND_RESULT, name, board.n,
                board.finishedAtMs - board.startedAtMs, board.moves, true, null)
        // Рекорды обновляются сразу, чтобы результатная панель уже в следующем кадре
        // знала, улучшено ли лучшее время или минимальное число ходов.
        val update = records.update(name, board.n, entry.elapsedMs, board.moves)
        board.isNewRecord = update.isNewRecord
        io.execute {
            history.add(entry)
            progress.markCollected(name)   // открывает фигурку в коллекции
        }
    }

    // читает картинку экрана (в GL-потоке), а кодирование и запись отдаёт в фоновый поток
    private fun capturePhoto(width: Int, height: Int, callback: (Boolean) -> Unit) {
        val rgba = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        val name = activeName
        val board = name?.let { boards[it] }
        val info = HistoryEntry(System.currentTimeMillis(), HistoryEntry.KIND_PHOTO, name ?: "",
                board?.n ?: settings.grid, board?.status()?.elapsedMs(SystemClock.elapsedRealtime()) ?: 0L,
                board?.moves ?: 0, board?.solved ?: false, null)
        io.execute {
            val saved = try {
                photoSaver.save(rgba, width, height, info)
            } catch (e: Exception) {
                Log.e("HelloAR", "photo failed", e)
                false
            }
            callback(saved)
        }
    }

    // подсказка: целая картинка точно поверх плиток. Она лежит в той же плоскости, что и плитки,
    // а глубина у разных треугольников одной плоскости совпадает не до бита, поэтому на время рисования
    // depth test пропускает всё (GL_ALWAYS), и картинка гарантированно ложится поверх
    private fun drawHint(projectionMatrix: Matrix44F, pose: Matrix44F, key: String, size: Vec2F) {
        GLES30.glDepthFunc(GLES30.GL_ALWAYS)
        tileRenderer!!.renderImage(projectionMatrix, pose, key, size, 0f, 0f)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    // рисует плитки пазла: в клетке cell лежит кусочек board.order[cell] (row 0 - верхний ряд)
    private fun drawBoard(projectionMatrix: Matrix44F, pose: Matrix44F, key: String, size: Vec2F, board: PuzzleBoard) {
        val n = board.n
        // тёмная подложка: настоящая картинка не просвечивает в щелях и под выбранной плиткой
        tileRenderer!!.renderSolid(projectionMatrix, pose, 0f, 0f,
                size.data[0] * BACKDROP_SCALE, size.data[1] * BACKDROP_SCALE, 0f, 0f, 0f, settings.dim)
        val gap = if (board.solved) 0f else TILE_GAP
        val cellW = size.data[0] / n
        val cellH = size.data[1] / n
        for (cell in 0 until n * n) {
            val row = cell / n
            val col = cell % n
            val piece = board.order[cell]
            val pieceRow = piece / n
            val pieceCol = piece % n
            val k = if (cell == board.selected) SELECTED_SCALE else 1f - gap
            tileRenderer!!.renderTile(projectionMatrix, pose, key,
                    -size.data[0] / 2 + cellW * (col + 0.5f), size.data[1] / 2 - cellH * (row + 0.5f),
                    cellW * k, cellH * k,
                    pieceCol.toFloat() / n, pieceRow.toFloat() / n, (pieceCol + 1).toFloat() / n, (pieceRow + 1).toFloat() / n)
        }
    }

    fun recreate_context() {
        if (bgRenderer != null) {
            bgRenderer!!.dispose()
            bgRenderer = null
        }
        if (tileRenderer != null) {
            tileRenderer!!.dispose()
            tileRenderer = null
        }
        modelRenderer?.dispose()
        modelRenderer = null
        modelState.clear()   // после потери контекста модели нужно загрузить в видеопамять заново
        previousInputFrameIndex = -1
        bgRenderer = BGRenderer()
        tileRenderer = TileRenderer()
        modelRenderer = ModelRenderer()
    }

    fun initialize() {
        recreate_context()

        camera = CameraDeviceSelector.createCameraDevice(CameraDevicePreference.PreferObjectSensing)
        throttler = InputFrameThrottler.create()
        inputFrameFork = InputFrameFork.create(2)
        join = OutputFrameJoin.create(2)
        oFrameBuffer = OutputFrameBuffer.create()
        i2OAdapter = InputFrameToOutputFrameAdapter.create()
        i2FAdapter = InputFrameToFeedbackFrameAdapter.create()
        outputFrameFork = OutputFrameFork.create(2)

        var status = true
        status = status and camera!!.openWithPreferredType(CameraDeviceType.Back)
        camera!!.setSize(Vec2I(1280, 960))
        camera!!.setFocusMode(CameraDeviceFocusMode.Continousauto)
        if (!status) {
            return
        }
        val tracker = ImageTracker.create()
        // пазлы берутся из папок assets/puzzles/<имя>/target.jpg
        val puzzles = PuzzleCatalog.load(assets)
        puzzleDefs = puzzles.associateBy { it.id }
        if (puzzles.isEmpty()) {
            Log.e("HelloAR", "no puzzles found in assets/" + PuzzleCatalog.ROOT)
        }
        for (def in puzzles) {
            loadFromImage(tracker, def.targetPath, def.id)
        }
        tracker.setSimultaneousNum(maxOf(1, minOf(puzzles.size, 4)))
        trackers.add(tracker)

        feedbackFrameFork = FeedbackFrameFork.create(trackers.size)

        camera!!.inputFrameSource().connect(throttler!!.input())
        throttler!!.output().connect(inputFrameFork!!.input())
        inputFrameFork!!.output(0).connect(i2OAdapter!!.input())
        i2OAdapter!!.output().connect(join!!.input(0))

        inputFrameFork!!.output(1).connect(i2FAdapter!!.input())
        i2FAdapter!!.output().connect(feedbackFrameFork!!.input())
        var k = 0
        var trackerBufferRequirement = 0
        for (_tracker in trackers) {
            feedbackFrameFork!!.output(k).connect(_tracker.feedbackFrameSink())
            _tracker.outputFrameSource().connect(join!!.input(k + 1))
            trackerBufferRequirement += _tracker.bufferRequirement()
            k++
        }

        join!!.output().connect(outputFrameFork!!.input())
        outputFrameFork!!.output(0).connect(oFrameBuffer!!.input())
        outputFrameFork!!.output(1).connect(i2FAdapter!!.sideInput())
        oFrameBuffer!!.signalOutput().connect(throttler!!.signalInput())

        //CameraDevice and rendering each require an additional buffer
        camera!!.setBufferCapacity(throttler!!.bufferRequirement() + i2FAdapter!!.bufferRequirement() + oFrameBuffer!!.bufferRequirement() + trackerBufferRequirement + 2)
    }

    fun dispose() {
        for (tracker in trackers) {
            tracker.dispose()
        }
        trackers.clear()
        if (bgRenderer != null) {
            bgRenderer!!.dispose()
            bgRenderer = null
        }
        if (tileRenderer != null) {
            tileRenderer!!.dispose()
            tileRenderer = null
        }
        modelRenderer?.dispose()
        modelRenderer = null
        for (loaded in loadedModels.values) {
            for (bitmap in loaded.bitmaps.values) {
                bitmap.recycle()
            }
        }
        loadedModels.clear()
        if (camera != null) {
            camera!!.dispose()
            camera = null
        }
        if (scheduler != null) {
            scheduler!!.dispose()
            scheduler = null
        }
        loader.shutdownNow()
        io.shutdown()   // уже поставленные в очередь записи успеют завершиться
    }

    fun start(): Boolean {
        var status = true
        if (camera != null) {
            status = status and camera!!.start()
        } else {
            status = false
        }
        for (tracker in trackers) {
            status = status and tracker.start()
        }
        return status
    }

    fun stop() {
        if (camera != null) {
            camera!!.stop()
        }
        for (tracker in trackers) {
            tracker.stop()
        }
    }

    fun render(width: Int, height: Int, screenRotation: Int) {
        while (scheduler!!.runOne()) {
        }

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val oframe = oFrameBuffer!!.peek() ?: return
        val iframe = oframe.inputFrame()
        val cameraParameters = iframe.cameraParameters()
        val viewport_aspect_ratio = width.toFloat() / height.toFloat()
        val imageProjection = cameraParameters.imageProjection(viewport_aspect_ratio, screenRotation, true, false)
        val image = iframe.image()

        try {
            if (iframe.index() != previousInputFrameIndex) {
                val buffer = image.buffer()
                try {
                    if ((imageBytes == null) || (imageBytes!!.size != buffer.size())) {
                        imageBytes = ByteArray(buffer.size())
                    }
                    buffer.copyToByteArray(imageBytes!!)
                    bgRenderer!!.upload(image.format(), image.width(), image.height(), image.pixelWidth(), image.pixelHeight(), ByteBuffer.wrap(imageBytes!!))
                } finally {
                    buffer.dispose()
                }
                previousInputFrameIndex = iframe.index()
            }
            bgRenderer!!.render(imageProjection)

            val projectionMatrix = cameraParameters.projection(0.01f, 1000f, viewport_aspect_ratio, screenRotation, true, false)
            var touch = pendingTouch.getAndSet(null)  // касание живёт один кадр
            uploadLoadedModels()
            if (reshuffleRequest.getAndSet(false)) {
                activeName?.let { boards[it]?.shuffle() }
            }
            var firstVisible: String? = null
            var activeVisible = false
            for (oResult in oframe.results()) {
                val result = oResult as? ImageTrackerResult
                if (result != null) {
                    for (targetInstance in result.targetInstances()) {
                        val target = targetInstance.target()
                        val imagetarget = (if (target is ImageTarget) target else null)
                                ?: continue
                        val images = (target as ImageTarget).images()
                        val targetImg = images[0]
                        val targetScale = imagetarget.scale()
                        val scale = Vec2F(targetScale, targetScale * targetImg.height() / targetImg.width())
                        val targetName = imagetarget.name()
                        ensureTexture(targetName)
                        val pose = targetInstance.pose()
                        // пазл создаётся при первом появлении картинки и заново, если в настройках сменили размер
                        val existing = boards[targetName]
                        val board = if (existing != null && existing.n == settings.grid) {
                            existing
                        } else {
                            val b = PuzzleBoard(settings.grid)
                            b.shuffle()
                            boards[targetName] = b
                            b
                        }
                        if (firstVisible == null) {
                            firstVisible = targetName
                        }
                        if (targetName == activeName) {
                            activeVisible = true
                        }
                        if (touch != null) {
                            val p = PuzzleMath.touchToTarget(projectionMatrix.data, pose.data, touch[0], touch[1])
                            if (p != null) {
                                val cell = PuzzleMath.cellAt(p[0], p[1], scale.data[0], scale.data[1], board.n)
                                if (cell >= 0) {
                                    touch = null
                                    val swapped = board.tap(cell, SystemClock.elapsedRealtime())
                                    if (swapped && board.solved) {
                                        recordResult(targetName, board)
                                    }
                                    activeName = targetName
                                    activeVisible = true
                                    Log.i("HelloAR", "puzzle $targetName: tap cell $cell, selected ${board.selected}, moves ${board.moves}, solved ${board.solved}")
                                }
                            }
                        }
                        drawBoard(projectionMatrix, pose, targetName, scale, board)
                        if (hintVisible) {
                            drawHint(projectionMatrix, pose, targetName, scale)
                        }
                        if (!modelState.containsKey(targetName)) {
                            requestModel(targetName)
                        }
                        if (board.solved) {
                            drawReward(projectionMatrix, pose, targetName, scale, board)
                        }
                        for (img in images) {
                            img.dispose()
                        }
                    }
                    result.dispose()
                }
            }
            // интерфейс следит за пазлом, который сейчас в кадре (после тапа - за тем, по которому попали)
            if (!activeVisible && firstVisible != null) {
                activeName = firstVisible
            }
            if (firstVisible != null) {
                lastSeenMs = SystemClock.elapsedRealtime()
            }
            status = activeName?.let { name -> boards[name]?.status(name) }
            // снимок экрана делается после того, как всё нарисовано
            val photoCallback = photoRequest.getAndSet(null)
            if (photoCallback != null) {
                capturePhoto(width, height, photoCallback)
            }
        } finally {
            iframe.dispose()
            oframe.dispose()
            cameraParameters.dispose()
            image.dispose()
        }
    }
}
