package com.github.musicyou.update

import java.math.BigInteger

class UpdateVersion private constructor(
    private val core: List<BigInteger>, private val prerelease: List<String>
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        core.indices.forEach { index -> core[index].compareTo(other.core[index]).let { if (it != 0) return it } }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val format = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(value: String): UpdateVersion {
            val match = format.matchEntire(value.removePrefix("v")) ?: error("Invalid release version")
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            require(prerelease.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) { "Invalid release version" }
            return UpdateVersion((1..3).map { match.groupValues[it].toBigInteger() }, prerelease)
        }
    }
}

fun shouldOfferUpdate(remoteName: String, remoteCode: Long, installedName: String, installedCode: Long): Boolean =
    remoteCode > installedCode && UpdateVersion.parse(remoteName) > UpdateVersion.parse(installedName)

fun requireValidChecksum(value: String) {
    require(Regex("[0-9a-fA-F]{64}").matches(value) && value.any { it != '0' }) { "Invalid APK checksum" }
}
