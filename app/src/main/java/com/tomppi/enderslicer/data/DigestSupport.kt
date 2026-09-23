package com.tomppi.enderslicer.data

import java.security.MessageDigest

/** Lowercase hex rendering of a digest, shared by the persisted stores. */
internal fun MessageDigest.hexDigest(): String =
    digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
