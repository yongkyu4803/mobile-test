package com.example.todo

data class Task(
    val id: Long,
    val title: String,
    val done: Boolean = false,
    /** 마감 시각 (epoch millis). 없으면 null. */
    val dueAt: Long? = null,
)
