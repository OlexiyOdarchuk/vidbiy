package ua.vidbiy

import kotlin.random.Random

/** Як вимикається сигнал: одним дотиком, прикладом чи струшуванням. */
enum class DismissTask(val title: String) {
    NONE("Одним дотиком"),
    MATH("Розв'язати приклад"),
    SHAKE("Струснути телефон"),
}

enum class MathLevel(val title: String, val sample: String) {
    EASY("Легкий", "47 + 38"),
    MEDIUM("Середній", "36 × 7"),
    HARD("Складний", "23 × 8 + 57"),
}

data class MathProblem(val text: String, val answer: Int)

object MathTasks {
    fun generate(level: MathLevel, random: Random = Random.Default): MathProblem = when (level) {
        MathLevel.EASY -> {
            val a = random.nextInt(11, 90)
            val b = random.nextInt(11, 90)
            MathProblem("$a + $b", a + b)
        }
        MathLevel.MEDIUM -> {
            val a = random.nextInt(12, 60)
            val b = random.nextInt(3, 10)
            MathProblem("$a × $b", a * b)
        }
        MathLevel.HARD -> {
            val a = random.nextInt(12, 60)
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, 90)
            MathProblem("$a × $b + $c", a * b + c)
        }
    }
}
