package com.example.musicfy.core.platform

object StdoutLogger : Logger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")

    override fun i(tag: String, msg: String) = println("I/$tag: $msg")

    override fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("W/$tag: $msg")
        t?.printStackTrace()
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
