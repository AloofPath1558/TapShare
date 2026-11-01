package app.tapshare

import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Drives the status block from layout/view_state.xml (dot, title, detail, progress, button).
 * Every call can be made from any thread.
 */
class StateUi(private val root: View) {
    private val pulse: PulseView = root.findViewById(R.id.pulse)
    private val title: TextView = root.findViewById(R.id.stateTitle)
    private val detail: TextView = root.findViewById(R.id.stateDetail)
    private val bar: ProgressBar = root.findViewById(R.id.progress)
    private val button: Button = root.findViewById(R.id.btnAction)

    fun set(
        titleText: CharSequence,
        detailText: CharSequence = "",
        active: Boolean = false,
        error: Boolean = false,
    ) {
        root.post {
            title.text = titleText
            title.setTextColor(
                ContextCompat.getColor(root.context, if (error) R.color.danger else R.color.ink),
            )
            detail.text = detailText
            detail.visibility = if (detailText.isEmpty()) View.GONE else View.VISIBLE
            pulse.error = error
            pulse.active = active
        }
    }

    fun progress(done: Long, total: Long) {
        root.post {
            bar.visibility = View.VISIBLE
            bar.setFraction(done, total)
        }
    }

    fun hideProgress() {
        root.post { bar.visibility = View.GONE }
    }

    /** Shows a button under the status, or hides it when [label] is null. */
    fun action(label: CharSequence?, onClick: () -> Unit = {}) {
        root.post {
            if (label == null) {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            } else {
                button.text = label
                button.visibility = View.VISIBLE
                button.setOnClickListener { onClick() }
            }
        }
    }
}
