package com.rocketgs.core

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns roll/pitch/yaw into "how far the nose leans from vertical, and toward
 * which compass direction" for the attitude scope. Same convention handling as
 * the desktop 3D view:
 *  - ZYX Euler (yaw, pitch, roll) in North-East-Down (usual IMU fusion output)
 *  - which body axis is the nose, and per-angle sign flips, are settings
 *  - "Set upright" (tare) makes everything relative to the moment it was
 *    pressed with the rocket standing on the pad; then the nose axis does not matter.
 * Display only: never use it for a safety decision.
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(b: Quat) = Quat(
        w * b.w - x * b.x - y * b.y - z * b.z,
        w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x,
        w * b.z + x * b.y - y * b.x + z * b.w)
    fun conj() = Quat(w, -x, -y, -z)
    /** Rotate vector v by this quaternion. */
    fun rotate(v: DoubleArray): DoubleArray {
        val p = this * Quat(0.0, v[0], v[1], v[2]) * conj()
        return doubleArrayOf(p.x, p.y, p.z)
    }
    companion object {
        fun axisAngle(ax: Double, ay: Double, az: Double, rad: Double): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az); val s = sin(rad / 2)
            return Quat(cos(rad / 2), ax / n * s, ay / n * s, az / n * s)
        }
        val NED_TO_ENU = Quat(0.0, Math.sqrt(0.5), Math.sqrt(0.5), 0.0)   // swap N/E, Down -> Up
    }
}

data class AttitudeSettings(
    val noseAxis: Int = 0,          // 0:+X 1:-X 2:+Y 3:-Y 4:+Z 5:-Z  (body axis along the nose)
    val flipRoll: Boolean = false,
    val flipPitch: Boolean = false,
    val flipYaw: Boolean = false,
)

/** tilt: 0 = nose straight up. toward: compass bearing the nose leans to (0 = N). */
data class Tilt(val tiltDeg: Double, val towardDeg: Double)

object Attitude {
    private val NOSE = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0))

    /** Body -> East-North-Up rotation from the reported angles. */
    fun bodyToEnu(rollDeg: Double, pitchDeg: Double, yawDeg: Double, s: AttitudeSettings): Quat {
        val r = Math.toRadians(if (s.flipRoll) -rollDeg else rollDeg)
        val p = Math.toRadians(if (s.flipPitch) -pitchDeg else pitchDeg)
        val y = Math.toRadians(if (s.flipYaw) -yawDeg else yawDeg)
        val ned = Quat.axisAngle(0.0, 0.0, 1.0, y) * Quat.axisAngle(0.0, 1.0, 0.0, p) * Quat.axisAngle(1.0, 0.0, 0.0, r)
        return Quat.NED_TO_ENU * ned
    }

    /**
     * Nose direction in ENU. With a tare, the nose is "whatever pointed up at
     * tare time": rotate world-up by the change since the tare.
     */
    fun tilt(rollDeg: Double, pitchDeg: Double, yawDeg: Double, s: AttitudeSettings, tare: Quat?): Tilt {
        val q = bodyToEnu(rollDeg, pitchDeg, yawDeg, s)
        val nose = if (tare != null) (q * tare.conj()).rotate(doubleArrayOf(0.0, 0.0, 1.0))
                   else q.rotate(NOSE[s.noseAxis.coerceIn(0, 5)])
        val tiltDeg = Math.toDegrees(acos(nose[2].coerceIn(-1.0, 1.0)))
        var az = Math.toDegrees(atan2(nose[0], nose[1]))
        if (az < 0) az += 360.0
        return Tilt(tiltDeg, az)
    }
}
