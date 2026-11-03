package dev.kpetriv.sprout.core.games

/**
 * A memory-game symbol.
 *
 * @property id stable machine name, for example `"apple"` or `"red-circle"`.
 * @property label the name for read-aloud and content descriptions, for example `"red circle"`.
 * @property glyph the emoji to draw.
 */
data class Symbol(val id: String, val label: String, val glyph: String)

/** The fixed symbol sets: pictures for Seedling, colours and shapes for Sprout and Sapling. */
object MemorySymbols {
    val pictures: List<Symbol> = listOf(
        Symbol("apple", "apple", "🍎"),
        Symbol("banana", "banana", "🍌"),
        Symbol("dog", "dog", "🐶"),
        Symbol("cat", "cat", "🐱"),
        Symbol("fish", "fish", "🐟"),
        Symbol("flower", "flower", "🌸"),
        Symbol("sun", "sun", "☀️"),
        Symbol("moon", "moon", "🌙"),
        Symbol("car", "car", "🚗"),
        Symbol("ball", "ball", "⚽"),
        Symbol("house", "house", "🏠"),
        Symbol("tree", "tree", "🌳"),
    )

    val coloursAndShapes: List<Symbol> = listOf(
        Symbol("red-circle", "red circle", "🔴"),
        Symbol("green-circle", "green circle", "🟢"),
        Symbol("blue-circle", "blue circle", "🔵"),
        Symbol("yellow-circle", "yellow circle", "🟡"),
        Symbol("red-square", "red square", "🟥"),
        Symbol("green-square", "green square", "🟩"),
        Symbol("blue-square", "blue square", "🟦"),
        Symbol("yellow-square", "yellow square", "🟨"),
        Symbol("star", "star", "⭐"),
        Symbol("heart", "heart", "❤️"),
        Symbol("triangle", "triangle", "🔺"),
        Symbol("diamond", "diamond", "🔷"),
    )

    fun forDifficulty(difficulty: Difficulty): List<Symbol> = when (difficulty) {
        Difficulty.SEEDLING -> pictures
        Difficulty.SPROUT, Difficulty.SAPLING -> coloursAndShapes
    }
}
