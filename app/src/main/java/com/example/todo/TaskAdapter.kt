package com.example.todo

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.todo.databinding.ItemTaskBinding

class TaskAdapter(
    private val onToggle: (Task) -> Unit,
    private val onEdit: (Task) -> Unit,
    private val onDelete: (Task) -> Unit,
) : ListAdapter<Task, TaskAdapter.Holder>(Diff) {

    inner class Holder(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val task = getItem(position)
        with(holder.b) {
            title.text = task.title
            title.paintFlags = if (task.done) {
                title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            title.alpha = if (task.done) 0.5f else 1f
            // 리스너를 먼저 해제해야 재바인딩 시 콜백이 중복 호출되지 않는다.
            check.setOnCheckedChangeListener(null)
            check.isChecked = task.done
            check.setOnCheckedChangeListener { _, _ -> onToggle(task) }
            root.setOnClickListener { onEdit(task) }
            deleteButton.setOnClickListener { onDelete(task) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(a: Task, b: Task) = a.id == b.id
        override fun areContentsTheSame(a: Task, b: Task) = a == b
    }
}
