package com.example.rusticpriceconvertor

fun trimZeros(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.3f", v)