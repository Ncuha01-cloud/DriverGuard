package com.driverguard.eyetrack.detection

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Обёртка над ML Kit Face Detection.
 *
 * Просим контуры лица (глаза/губы) и углы Эйлера головы. Классификация
 * «глаз открыт/закрыт» у ML Kit есть только для крупных фронтальных лиц и в
 * машине нестабильна, поэтому открытость глаз считаем сами по контуру.
 */
class FaceAnalyzer {

    data class Frame(
        val face: Face?,
        val ear: Float,
        val pitch: Float,
        val yaw: Float,
        val roll: Float,
        val mouthRatio: Float,
        val leftEyeOpenProb: Float?,
        val rightEyeOpenProb: Float?,
        /** Нормированный box лица и контуры глаз в координатах 0..1 */
        val box: FloatArray?,
        val eyes: List<FloatArray>,
    )

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    private val normalizer = DrowsinessEngine.EarNormalizer()

    fun reset() = normalizer.reset()

    fun close() {
        runCatching { detector.close() }
    }

    suspend fun analyze(image: InputImage): Frame {
        val faces = detector.process(image).await()
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return Frame(null, Float.NaN, 0f, 0f, 0f, 0f, null, null, null, emptyList())
        return toFrame(face, image.width, image.height)
    }

    private fun toFrame(face: Face, imgW: Int, imgH: Int): Frame {
        val left = face.getContour(FaceContour.LEFT_EYE)?.points?.map { floatArrayOf(it.x, it.y) }
        val right = face.getContour(FaceContour.RIGHT_EYE)?.points?.map { floatArrayOf(it.x, it.y) }

        val lRaw = left?.let { DrowsinessEngine.Eyes.rawRatio(it) } ?: Float.NaN
        val rRaw = right?.let { DrowsinessEngine.Eyes.rawRatio(it) } ?: Float.NaN
        var raw = when {
            lRaw.isNaN() && rRaw.isNaN() -> Float.NaN
            lRaw.isNaN() -> rRaw
            rRaw.isNaN() -> lRaw
            else -> (lRaw + rRaw) / 2f
        }

        // Если ML Kit отдал вероятности открытых глаз — мягко смешиваем их
        // с геометрией контура (когда лицо достаточно крупное).
        val lp = face.leftEyeOpenProbability
        val rp = face.rightEyeOpenProbability
        if (!raw.isNaN() && lp != null && rp != null) {
            val prob = (lp + rp) / 2f
            raw = raw * 0.8f + prob * 0.25f
        }

        val eulerX = face.headEulerAngleX
        // ML Kit: X<0 — лицо наклонено вниз (кивок). Переводим в «вниз > 0».
        val pitchDown = -eulerX

        val bb = face.boundingBox
        val mouthRatio = if (bb.width() > 0) mouthRatio(face, bb.width()) else 0f

        return Frame(
            face = face,
            ear = normalizer.normalize(raw, !raw.isNaN() && raw > 0.30f),
            pitch = pitchDown,
            yaw = face.headEulerAngleY,
            roll = face.headEulerAngleZ,
            mouthRatio = mouthRatio,
            leftEyeOpenProb = lp,
            rightEyeOpenProb = rp,
            box = normalizedBox(face, imgW, imgH),
            eyes = listOfNotNull(left?.flattenNorm(imgW, imgH), right?.flattenNorm(imgW, imgH)),
        )
    }

    /** Плоский массив [x0,y0,x1,y1,...] в координатах 0..1 для оверлея. */
    private fun List<FloatArray>.flattenNorm(w: Int, h: Int): FloatArray {
        val out = FloatArray(size * 2)
        forEachIndexed { i, p ->
            out[i * 2] = p[0] / w
            out[i * 2 + 1] = p[1] / h
        }
        return out
    }

    /** Раскрытие рта как (низ нижней губы − верх верхней) / ширина лица. */
    private fun mouthRatio(face: Face, faceWidth: Int): Float {
        val upper = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: return 0f
        val lower = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: return 0f
        if (upper.isEmpty() || lower.isEmpty()) return 0f
        val topY = upper.minOf { it.y }
        val bottomY = lower.maxOf { it.y }
        return ((bottomY - topY) / faceWidth).coerceIn(0f, 1.5f)
    }

    companion object {
        /** Нормализованный bounding box лица относительно изображения [l, t, r, b] 0..1 */
        fun normalizedBox(face: Face?, width: Int, height: Int): FloatArray? {
            if (face == null || width <= 0 || height <= 0) return null
            val b = face.boundingBox
            return floatArrayOf(
                b.left.toFloat() / width,
                b.top.toFloat() / height,
                b.right.toFloat() / width,
                b.bottom.toFloat() / height,
            )
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    addOnCanceledListener { cont.cancel() }
}
