package app.fusion.wallet.data

data class BenchmarkMetric(val label: String, val numerator: Int, val denominator: Int)
object BenchmarkMetrics {
    /** Read the measured AHEM-G rows, never the plain arm or development tuning rows. */
    fun parse(markdown: String): List<BenchmarkMetric> = listOf("Frozen Corpus" to "Frozen", "Held-Out Set" to "Held-out").flatMap { (key,label) ->
        val row=markdown.lineSequence().single { it.startsWith('|') && it.contains(key,true) && it.contains("AHEM-G") }
        val cells=row.split('|')
        listOf(3 to "ASR",4 to "FBR").map { (column,metric) ->
            val match=Regex("(\\d+)\\s*/\\s*(\\d+)").find(cells[column]) ?: error("Missing $label $metric")
            val n=match.groupValues[1].toInt(); val d=match.groupValues[2].toInt()
            require(d>0 && n in 0..d)
            BenchmarkMetric("$label $metric",n,d)
        }
    }
}
