package com.example.todo

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.todo.databinding.ActivityMainBinding
import com.example.todo.databinding.DialogEditBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, ACTIVE, DONE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TaskStore
    private lateinit var adapter: TaskAdapter
    private var tasks = mutableListOf<Task>()
    private var filter = Filter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = TaskStore(this)
        tasks = store.load()

        adapter = TaskAdapter(
            onToggle = { t -> update(t.copy(done = !t.done)) },
            onEdit = ::showEditDialog,
            onDelete = ::delete,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        attachSwipeToDelete()

        binding.addButton.setOnClickListener { addFromInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput()
                true
            } else {
                false
            }
        }

        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chipActive -> Filter.ACTIVE
                R.id.chipDone -> Filter.DONE
                else -> Filter.ALL
            }
            render()
        }

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_clear_done) {
                tasks.removeAll { t -> t.done }
                persistAndRender()
                true
            } else {
                false
            }
        }

        render()
    }

    private fun addFromInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // 새 항목이 가장 위에 보이도록 앞에 추가한다.
        tasks.add(0, Task(id = System.currentTimeMillis(), title = text))
        binding.input.text?.clear()
        persistAndRender()
        binding.list.scrollToPosition(0)
    }

    private fun update(task: Task) {
        val i = tasks.indexOfFirst { it.id == task.id }
        if (i >= 0) {
            tasks[i] = task
            persistAndRender()
        }
    }

    private fun delete(task: Task) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        tasks.removeAt(index)
        persistAndRender()
        Snackbar.make(binding.root, R.string.deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                tasks.add(index.coerceAtMost(tasks.size), task)
                persistAndRender()
            }
            .show()
    }

    private fun showEditDialog(task: Task) {
        val dialogBinding = DialogEditBinding.inflate(layoutInflater)
        val input: EditText = dialogBinding.editInput
        input.setText(task.title)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) update(task.copy(title = text))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) delete(adapter.currentList[pos])
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.list)
    }

    private fun persistAndRender() {
        store.save(tasks)
        render()
    }

    private fun render() {
        val visible = when (filter) {
            Filter.ALL -> tasks
            Filter.ACTIVE -> tasks.filter { !it.done }
            Filter.DONE -> tasks.filter { it.done }
        }
        adapter.submitList(visible.toList())
        binding.empty.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.summary.text = getString(R.string.summary, tasks.count { !it.done }, tasks.size)
    }
}
