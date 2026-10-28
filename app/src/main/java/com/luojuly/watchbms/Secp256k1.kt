package com.luojuly.watchbms

import java.math.BigInteger
import java.security.SecureRandom

/**
 * secp256k1 in plain arithmetic. The DL handshake sends an uncompressed X||Y public key
 * and uses the X coordinate of the shared point. This watch's Android build does not offer the curve.
 */
internal object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val G = Point(GX, GY)

    private class Point(val x: BigInteger, val y: BigInteger)

    fun privateKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            val scalar = BigInteger(1, bytes)
            if (scalar.signum() > 0 && scalar < N) return fixed32(scalar)
        }
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        val point = multiply(BigInteger(1, privateKey), G) ?: error("invalid private key")
        return encode(point)
    }

    fun shared(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val peer = decode(peerPublicKey)
        val point = multiply(BigInteger(1, privateKey), peer) ?: error("degenerate shared point")
        return fixed32(point.x)
    }

    private fun encode(point: Point): ByteArray {
        val out = ByteArray(64)
        fixed32(point.x).copyInto(out, 0)
        fixed32(point.y).copyInto(out, 32)
        return out
    }

    private fun decode(xy: ByteArray): Point {
        require(xy.size == 64) { "public key must be 64 bytes" }
        val point = Point(BigInteger(1, xy.copyOfRange(0, 32)), BigInteger(1, xy.copyOfRange(32, 64)))
        require(onCurve(point)) { "public key is not on secp256k1" }
        return point
    }

    private fun onCurve(point: Point): Boolean {
        if (point.x.signum() < 0 || point.y.signum() < 0 || point.x >= P || point.y >= P) return false
        val left = point.y.multiply(point.y).mod(P)
        val right = point.x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        return left == right
    }

    private fun multiply(scalar: BigInteger, point: Point): Point? {
        var result: Point? = null
        var addend: Point? = point
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        val lambda = if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null
            a.x.multiply(a.x).multiply(BigInteger.valueOf(3)).multiply(a.y.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).mod(P).modInverse(P)).mod(P)
        }
        val x = lambda.multiply(lambda).subtract(a.x).subtract(b.x).mod(P)
        val y = lambda.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val copy = minOf(32, raw.size)
        System.arraycopy(raw, raw.size - copy, out, 32 - copy, copy)
        return out
    }
}
