package com.felix.closet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Vision-capable providers, all OpenAI-compatible chat/completions with image_url blocks.
 * Default model names are carried over from ChatPilot; the user can override them.
 *
 * @param thinkingParam  accepts {"thinking":{"type":"disabled"}}; we send it to keep recognition fast,
 *                       and retry once without it if the server rejects the request.
 */
enum class Provider(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val omitTemperature: Boolean = false,
    val newTokenParam: Boolean = false,
    val thinkingParam: Boolean = false,
    val overseas: Boolean = false,
) {
    DOUBAO("豆包（火山方舟）", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-2-0-mini-260428", thinkingParam = true),
    QWEN("通义千问（阿里百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-vl-plus"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6v", thinkingParam = true),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1/chat/completions", "Qwen/Qwen3-VL-32B-Instruct"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5-mini",
        omitTemperature = true, newTokenParam = true, overseas = true),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.8-flash",
        overseas = true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-5-mini",
        omitTemperature = true, overseas = true),
    CUSTOM("自定义（OpenAI 兼容）", "", "");

    val menuLabel: String get() = if (overseas) "$label · 需代理" else label
}

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", Provider.DOUBAO.name)!!) }
            .getOrDefault(Provider.DOUBAO)
        set(v) = sp.edit().putString("provider", v.name).apply()

    fun apiKey(p: Provider): String = sp.getString("key_" + p.name, "") ?: ""
    fun setApiKey(p: Provider, v: String) = sp.edit().putString("key_" + p.name, v.trim()).apply()

    fun model(p: Provider): String = rawModel(p).ifBlank { p.defaultModel }
    fun rawModel(p: Provider): String = sp.getString("model_" + p.name, "") ?: ""
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_" + p.name, v.trim()).apply()

    fun rawEndpoint(p: Provider): String = sp.getString("endpoint_" + p.name, "") ?: ""
    fun setEndpoint(p: Provider, v: String) = sp.edit().putString("endpoint_" + p.name, v.trim()).apply()

    fun endpoint(p: Provider): String {
        if (p != Provider.CUSTOM) return p.endpoint
        val raw = rawEndpoint(p).trim().trimEnd('/')
        if (raw.isEmpty()) return ""
        return if (raw.endsWith("/chat/completions")) raw else "$raw/chat/completions"
    }

    fun missing(p: Provider): String = when {
        apiKey(p).isBlank() -> "还没填写 ${p.label} 的 API Key"
        p == Provider.CUSTOM && endpoint(p).isBlank() -> "还没填写自定义接口地址"
        p == Provider.CUSTOM && rawModel(p).isBlank() -> "还没填写自定义模型名"
        p == Provider.CUSTOM && !endpoint(p).startsWith("https://") -> "接口地址要以 https:// 开头"
        else -> ""
    }

    var weather: Weather
        get() = runCatching { Weather.valueOf(sp.getString("weather", Weather.WARM.name)!!) }.getOrDefault(Weather.WARM)
        set(v) = sp.edit().putString("weather", v.name).apply()

    var occasion: String
        get() = (sp.getString("occasion", "日常") ?: "日常").takeIf { it in Occasion.ALL } ?: "日常"
        set(v) = sp.edit().putString("occasion", v).apply()
}

/** Thrown with a message that is safe to show to the user as-is. [raw] is the server's reply, for debugging. */
class VisionException(message: String, val raw: String? = null) : Exception(message)

/** One garment found in an image. [box] is in pixels of the image that was sent: left, top, right, bottom. */
data class Detected(
    val name: String,
    val cat: String,
    val color: String,
    val warmth: Int,
    val occasions: List<String>,
    val box: IntArray?,
)

object VisionPrompt {
    val SYSTEM = """
你是衣橱录入助手。用户发来一张图片，可能是购物订单截图（淘宝、京东、拼多多、抖音、得物等，可能有一件或多件商品），也可能是衣物的实拍照片。
请找出图里用户买的或拍的所有衣服、鞋子、包和配饰。忽略非服饰商品，忽略“为你推荐”“猜你喜欢”等推荐区域里的商品，同一件商品出现多次只输出一次。

每件输出：
- name：12 个字以内，颜色+款式，例如“黑色宽松连帽卫衣”，去掉品牌名和营销词
- cat：只能是 上装、下装、连衣裙、外套、鞋子、包、配饰 之一（半身裙算下装）
- color：主色，只能是 黑、白、灰、米、卡其、驼、棕、藏青、牛仔蓝、蓝、绿、红、粉、黄、橙、紫、花色 之一
- warmth：厚度，1=薄（夏季）、2=适中（春秋）、3=厚（冬季）
- occasions：适合的场合，从 日常、通勤、约会、运动、正式 中选 1-3 个
- box：[x1, y1, x2, y2]，商品图片在整张图中的位置，用 0-1000 的相对坐标（左上角为 0,0，右下角为 1000,1000）。订单截图框商品的缩略图，不要框文字；实拍照片框衣物本身。

只输出一个 JSON 对象，不要代码围栏，不要任何解释：
{"items":[{"name":"...","cat":"...","color":"...","warmth":2,"occasions":["日常"],"box":[x1,y1,x2,y2]}]}
如果图里没有服饰，输出 {"items":[],"note":"原因"}
""".trimIndent()

    const val USER = "识别这张图里的服饰。"
}

object VisionParse {

    /** Tolerates code fences, prose around the JSON, and a bare top-level array. */
    fun parse(content: String, imgW: Int, imgH: Int): Pair<List<Detected>, String> {
        val text = content.trim()
        val arr: JSONArray
        var note = ""
        val objStart = text.indexOf('{')
        val arrStart = text.indexOf('[')
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            val end = text.lastIndexOf(']')
            arr = runCatching { JSONArray(text.substring(arrStart, end + 1)) }
                .getOrElse { throw VisionException("模型返回的内容无法解析。", content.take(600)) }
        } else {
            val end = text.lastIndexOf('}')
            if (objStart < 0 || end <= objStart) throw VisionException("模型没有返回 JSON。", content.take(600))
            val o = runCatching { JSONObject(text.substring(objStart, end + 1)) }
                .getOrElse { throw VisionException("模型返回的 JSON 无法解析。", content.take(600)) }
            note = o.optString("note").trim()
            arr = o.optJSONArray("items") ?: JSONArray()
        }
        val out = mutableListOf<Detected>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().take(20)
            if (name.isEmpty()) continue
            val occ = mutableListOf<String>()
            o.optJSONArray("occasions")?.let { a ->
                for (j in 0 until a.length()) a.optString(j).trim().takeIf { it in Occasion.ALL && it !in occ }?.let(occ::add)
            }
            out += Detected(
                name = name,
                cat = Cat.normalize(o.optString("cat").ifEmpty { name }),
                color = Colors.normalize(o.optString("color").ifEmpty { name }),
                warmth = o.optInt("warmth", 2).coerceIn(1, 3),
                occasions = occ,
                box = box(o.opt("box"), imgW, imgH),
            )
        }
        return out to note
    }

    /**
     * Accepts [x1,y1,x2,y2] as 0–1000 relative (asked for), 0–1 fractions, or absolute pixels.
     * Returns null when the box is missing or too small to be a product picture.
     */
    fun box(v: Any?, w: Int, h: Int): IntArray? {
        val a = v as? JSONArray ?: return null
        if (a.length() < 4 || w <= 0 || h <= 0) return null
        val n = DoubleArray(4) { a.optDouble(it, Double.NaN) }
        if (n.any { it.isNaN() || it < 0 }) return null
        val max = n.max()
        val (sx, sy) = when {
            max <= 1.0 -> w.toDouble() to h.toDouble()
            max <= 1000.0 -> w / 1000.0 to h / 1000.0
            else -> 1.0 to 1.0
        }
        var x1 = (minOf(n[0], n[2]) * sx).toInt().coerceIn(0, w)
        var x2 = (maxOf(n[0], n[2]) * sx).toInt().coerceIn(0, w)
        var y1 = (minOf(n[1], n[3]) * sy).toInt().coerceIn(0, h)
        var y2 = (maxOf(n[1], n[3]) * sy).toInt().coerceIn(0, h)
        if (x2 - x1 < w * 0.03 || y2 - y1 < h * 0.01 || x2 - x1 < 16 || y2 - y1 < 16) return null
        // A little margin, models tend to box tight.
        val px = ((x2 - x1) * 0.03).toInt()
        val py = ((y2 - y1) * 0.03).toInt()
        x1 = (x1 - px).coerceAtLeast(0); x2 = (x2 + px).coerceAtMost(w)
        y1 = (y1 - py).coerceAtLeast(0); y2 = (y2 + py).coerceAtMost(h)
        return intArrayOf(x1, y1, x2, y2)
    }
}

class VisionClient(private val settings: Settings) {

    /** Blocking; call from a background thread. [jpegBase64] is the image exactly as sized for [w]×[h]. */
    fun recognize(jpegBase64: String, w: Int, h: Int): Pair<List<Detected>, String> {
        val p = settings.provider
        val missing = settings.missing(p)
        if (missing.isNotEmpty()) throw VisionException("$missing，请到「设置」里填写。")
        val content = try {
            call(p, jpegBase64, withThinking = p.thinkingParam)
        } catch (e: VisionException) {
            if (p.thinkingParam && e.message?.contains("HTTP 400") == true) call(p, jpegBase64, withThinking = false) else throw e
        }
        return VisionParse.parse(content, w, h)
    }

    private fun body(p: Provider, b64: String, withThinking: Boolean): JSONObject {
        val user = JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            .put(JSONObject().put("type", "text").put("text", VisionPrompt.USER))
        val o = JSONObject()
            .put("model", settings.model(p))
            .put(if (p.newTokenParam) "max_completion_tokens" else "max_tokens", 4096)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", VisionPrompt.SYSTEM))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (!p.omitTemperature) o.put("temperature", 0.2)
        if (withThinking) o.put("thinking", JSONObject().put("type", "disabled"))
        return o
    }

    private fun call(p: Provider, b64: String, withThinking: Boolean): String {
        val endpoint = settings.endpoint(p)
        val url = runCatching { URL(endpoint) }.getOrElse { throw VisionException("接口地址格式不对：$endpoint") }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey(p)}")
        }
        try {
            conn.outputStream.use { it.write(body(p, b64, withThinking).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val resp = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw VisionException("${hint(code)}（HTTP $code）", resp.take(600))
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = runCatching { JSONObject(text) }.getOrElse { throw VisionException("模型返回格式无法解析。", text.take(600)) }
            root.optJSONObject("error")?.let { throw VisionException("模型服务返回错误：${it.optString("message")}", text.take(600)) }
            val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw VisionException("模型返回里没有结果。", text.take(600))
            val content = if (msg.isNull("content")) "" else msg.optString("content")
            if (content.isBlank()) throw VisionException("模型返回了空内容，可能是模型不支持看图，换个模型试试。", text.take(600))
            return content
        } catch (e: VisionException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw VisionException("请求超时，网络慢或图片太大，请重试。")
        } catch (e: IOException) {
            throw VisionException("网络错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun hint(code: Int) = when (code) {
        400 -> "请求被拒绝，多半是这个模型不支持看图，请换一个能看图的模型"
        401, 403 -> "API Key 无效或没有权限"
        402 -> "账户余额不足"
        404 -> "模型名或接口地址不存在，请检查模型名"
        413 -> "图片太大"
        429 -> "请求太频繁或额度用尽，稍等再试"
        in 500..599 -> "模型服务暂时出错，稍后重试"
        else -> "请求失败"
    }
}
