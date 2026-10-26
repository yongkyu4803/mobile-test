package com.example.todo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.todo.databinding.ActivityMainBinding
import com.example.todo.databinding.DialogEditBinding
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, ACTIVE, DONE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TaskStore
    private lateinit var adapter: TaskAdapter
    private var tasks = mutableListOf<Task>()
    private var filter = Filter.ALL
    private var pendingDue: Long? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = TaskStore(this)
        tasks = store.load()
        // 예약이 유실됐을 수 있으니 시작할 때마다 다시 맞춘다.
        Reminders.syncAll(this, tasks)

        adapter = TaskAdapter(
            onToggle = { t -> update(t.copy(done = !t.done)) },
            onEdit = ::showEditDialog,
            onDelete = ::delete,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        attachSwipeToDelete()

        binding.dueButton.setOnClickListener {
            pickDateTime(pendingDue) { pendingDue = it; renderPendingDue() }
        }
        binding.clearDueButton.setOnClickListener {
            pendingDue = null
            renderPendingDue()
        }
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
                tasks.filter { t -> t.done }.forEach { t -> Reminders.cancel(this, t) }
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
        val task = Task(id = System.currentTimeMillis(), title = text, dueAt = pendingDue)
        tasks.add(0, task)
        Reminders.sync(this, task)
        binding.input.text?.clear()
        pendingDue = null
        renderPendingDue()
        persistAndRender()
        binding.list.scrollToPosition(0)
    }

    private fun update(task: Task) {
        val i = tasks.indexOfFirst { it.id == task.id }
        if (i >= 0) {
            tasks[i] = task
            Reminders.sync(this, task)
            persistAndRender()
        }
    }

    private fun delete(task: Task) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        tasks.removeAt(index)
        Reminders.cancel(this, task)
        persistAndRender()
        Snackbar.make(binding.root, R.string.deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                tasks.add(index.coerceAtMost(tasks.size), task)
                Reminders.sync(this, task)
                persistAndRender()
            }
            .show()
    }

    private fun showEditDialog(task: Task) {
        val dialogBinding = DialogEditBinding.inflate(layoutInflater)
        val input: EditText = dialogBinding.editInput
        input.setText(task.title)
        input.setSelection(input.text.length)
        var due = task.dueAt
        fun renderDue() {
            dialogBinding.dueButton.text =
                due?.let { getString(R.string.due_prefix, Reminders.formatDue(it)) } ?: getString(R.string.set_due)
            dialogBinding.clearDueButton.visibility = if (due == null) View.GONE else View.VISIBLE
        }
        renderDue()
        dialogBinding.dueButton.setOnClickListener { pickDateTime(due) { due = it; renderDue() } }
        dialogBinding.clearDueButton.setOnClickListener { due = null; renderDue() }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) update(task.copy(title = text, dueAt = due))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 날짜 → 시간 순서로 고르고, 알림 권한(Android 13+)이 없으면 함께 요청한다. */
    private fun pickDateTime(initial: Long?, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = initial ?: (System.currentTimeMillis() + 60 * 60 * 1000)
        }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                cal.set(y, m, d, h, min, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
                requestNotificationPermissionIfNeeded()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderPendingDue() {
        val due = pendingDue
        binding.dueButton.text =
            due?.let { getString(R.string.due_prefix, Reminders.formatDue(it)) } ?: getString(R.string.set_due)
        binding.clearDueButton.visibility = if (due == null) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 시간이 지나 기한 초과 표시가 바뀌었을 수 있으니 다시 그린다.
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
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
        binding.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.summary.text = getString(R.string.summary, tasks.count { !it.done }, tasks.size)
    }
}
