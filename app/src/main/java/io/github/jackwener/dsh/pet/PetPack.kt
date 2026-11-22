package io.github.jackwener.dsh.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import org.json.JSONObject

/** One cell of a sheet, shown for [ms]. */
data class PetFrame(val index: Int, val ms: Int)

/** One animation: a grid of cells on [sheet], cropped to [box] (art px). */
class PetAnimation(
    val name: String,
    val sheet: Bitmap,
    val frameWidth: Int,
    val frameHeight: Int,
    val cols: Int,
    val box: Rect,
    val frames: List<PetFrame>,
    val loop: Boolean,
) {
    /** Where cell [index] sits on the sheet. */
    fun srcRect(index: Int): Rect {
        val col = index % cols
        val row = index / cols
        return Rect(col * frameWidth, row * frameHeight, (col + 1) * frameWidth, (row + 1) * frameHeight)
    }
}

/**
 * A schema-1 pet pack read from `assets/pets/<id>/` (pack.json plus one frame grid per animation).
 * Only what the overlay draws is parsed: canvas, anchor, content box, animations and the state map.
 */
class PetPack(
    val id: String,
    val name: String,
    val scale: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val anchorX: Float,
    val baseline: Float,
    val content: Rect,
    val animations: Map<String, PetAnimation>,
    val states: Map<String, List<String>>,
) {
    /** The animation for a state name (or an animation name), falling back to the idle one. */
    fun animation(name: String?): PetAnimation? {
        if (name == null) return idle()
        animations[name]?.let { return it }
        states[name]?.firstOrNull()?.let { animations[it]?.let { a -> return a } }
        return idle()
    }

    fun idle(): PetAnimation? = animations[states["idle"]?.firstOrNull() ?: "idle"] ?: animations.values.firstOrNull()

    companion object {
        const val DEFAULT_ID = "teio"

        fun load(context: Context, id: String = DEFAULT_ID): PetPack? = try {
            val json = context.assets.open("pets/$id/pack.json").use { JSONObject(it.readBytes().decodeToString()) }
            val canvas = json.getJSONObject("canvas")
            val anchor = json.getJSONObject("anchor")
            val box = json.getJSONObject("contentBox")
            val animations = LinkedHashMap<String, PetAnimation>()
            val animJson = json.getJSONObject("animations")
            for (name in animJson.keys()) {
                val a = animJson.getJSONObject(name)
                val sheetName = a.getString("sheet")
                val bitmap = context.assets.open("pets/$id/$sheetName").use { BitmapFactory.decodeStream(it) } ?: continue
                val b = a.optJSONObject("box")
                val framesJson = a.getJSONArray("frames")
                val frames = ArrayList<PetFrame>(framesJson.length())
                for (i in 0 until framesJson.length()) {
                    val f = framesJson.getJSONObject(i)
                    frames += PetFrame(f.getInt("i"), f.getInt("ms"))
                }
                animations[name] = PetAnimation(
                    name = name,
                    sheet = bitmap,
                    frameWidth = a.getInt("frameW"),
                    frameHeight = a.getInt("frameH"),
                    cols = a.getInt("cols"),
                    box = if (b != null) Rect(b.getInt("x"), b.getInt("y"), b.getInt("x") + b.getInt("w"), b.getInt("y") + b.getInt("h")) else Rect(0, 0, bitmap.width, bitmap.height),
                    frames = frames,
                    loop = a.optBoolean("loop", true),
                )
            }
            val states = LinkedHashMap<String, List<String>>()
            json.optJSONObject("states")?.let { s ->
                for (key in s.keys()) {
                    val arr = s.optJSONArray(key) ?: continue
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list += arr.getString(i)
                    states[key] = list
                }
            }
            PetPack(
                id = json.optString("id", id),
                name = json.optString("name", id),
                scale = json.optInt("scale", 2),
                canvasWidth = canvas.getInt("w"),
                canvasHeight = canvas.getInt("h"),
                anchorX = anchor.getDouble("x").toFloat(),
                baseline = anchor.getDouble("baseline").toFloat(),
                content = Rect(box.getInt("x"), box.getInt("y"), box.getInt("x") + box.getInt("w"), box.getInt("y") + box.getInt("h")),
                animations = animations,
                states = states,
            )
        } catch (t: Throwable) {
            null
        }
    }
}
