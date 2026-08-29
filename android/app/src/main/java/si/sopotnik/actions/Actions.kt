package si.sopotnik.actions

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import si.sopotnik.IntentRouter
import si.sopotnik.NotifListener

/** Izvajanje dejanj na telefonu. Vrne kratek govorjeni "ack" ali opis napake. */
object Actions {

    fun resolveContact(ctx: Context, query: String): ContactMatch? {
        val q = IntentRouter.normalize(query).trim()
        if (q.isEmpty()) return null
        var best: ContactMatch? = null
        var bestScore = 0
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val number = cur.getString(1) ?: continue
                    val nn = IntentRouter.normalize(name)
                    val score = when {
                        nn == q -> 100
                        nn.startsWith("$q ") || nn.startsWith(q) -> 80
                        nn.split(' ').any { it.startsWith(q) } -> 70
                        q.split(' ').all { t -> nn.split(' ').any { it.startsWith(t) } } -> 65
                        nn.contains(q) -> 40
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = ContactMatch(name, number)
                    }
                }
            }
        }
        return best
    }

    fun execute(ctx: Context, action: Action, resolved: ContactMatch? = null): String = try {
        when (action) {
            is Action.Call -> {
                val c = resolved ?: error("kontakt ni razrešen")
                ctx.startActivity(
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.number)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Kličem ${c.name}."
            }

            is Action.OpenApp -> {
                val pm = ctx.packageManager
                val q = IntentRouter.normalize(action.query).trim()
                var bestPkg: String? = null
                var bestLabel = ""
                var bestScore = 0
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).forEach { ri ->
                    val label = ri.loadLabel(pm).toString()
                    val ln = IntentRouter.normalize(label)
                    val score = when {
                        ln == q -> 100
                        ln.startsWith(q) -> 80
                        ln.split(' ').any { it.startsWith(q) } -> 60
                        ln.contains(q) -> 40
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestPkg = ri.activityInfo.packageName
                        bestLabel = label
                    }
                }
                val pkg = bestPkg ?: error("aplikacije '${action.query}' ne najdem")
                val launch = pm.getLaunchIntentForPackage(pkg) ?: error("aplikacije ni mogoče zagnati")
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Odpiram $bestLabel."
            }

            is Action.MediaPlay -> {
                controller(ctx)?.transportControls?.play() ?: sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                "Velja."
            }

            is Action.MediaPause -> {
                controller(ctx)?.transportControls?.pause() ?: sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Ustavljeno."
            }

            is Action.MediaNext -> {
                controller(ctx)?.transportControls?.skipToNext() ?: sendKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
                "Naslednja."
            }

            is Action.MediaPrev -> {
                controller(ctx)?.transportControls?.skipToPrevious() ?: sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Prejšnja."
            }

            is Action.MediaPlaySearch -> when (action.app) {
                "spotify" -> {
                    ctx.startActivity(
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .setPackage("com.spotify.music")
                            .putExtra(SearchManager.QUERY, action.query)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "Iščem ${action.query} na Spotifyju."
                }

                // Privzeto (tudi brez navedene aplikacije) cilja YouTube Music, ki ob
                // play-from-search samodejno zaigra; navadni YouTube le odpre iskanje,
                // splet je zadnja rezerva. Generični intent brez paketa na HyperOS
                // pogosto ne naredi ničesar vidnega.
                else -> {
                    try {
                        ctx.startActivity(
                            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                                .setPackage("com.google.android.apps.youtube.music")
                                .putExtra(SearchManager.QUERY, action.query)
                                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        "Predvajam ${action.query} na YouTube Music."
                    } catch (e: Exception) {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_SEARCH)
                                    .setPackage("com.google.android.youtube")
                                    .putExtra("query", action.query)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            "Odpiram iskanje ${action.query} na YouTube — izberi posnetek."
                        } catch (e2: Exception) {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(action.query))
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            "Iščem ${action.query} na YouTube."
                        }
                    }
                }
            }

            is Action.Navigate -> {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(action.dest)}&mode=d"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Zaganjam navigacijo do ${action.dest}."
            }

            is Action.VolumeUp -> {
                audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "Glasneje."
            }

            is Action.VolumeDown -> {
                audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                "Tiše."
            }

            is Action.VolumeSet -> {
                val am = audio(ctx)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * action.percent / 100f), 0)
                "Glasnost na ${action.percent} odstotkov."
            }

            is Action.ReadNotifications -> {
                NotifListener.ensureBound(ctx)
                val list = NotifListener.snapshot()
                when {
                    list == null -> "Dostop do obvestil še ni pripravljen — odpri Sopotnik in poskusi znova."
                    list.isEmpty() -> "Ni novih obvestil."
                    else -> buildString {
                        append("Imaš ${list.size} obvestil. ")
                        list.take(5).forEachIndexed { i, n ->
                            append("${i + 1}: ${n.app}, ${n.title}. ${n.text.take(120)}. ")
                        }
                        if (list.size > 5) append("In še ${list.size - 5} drugih.")
                    }
                }
            }

            is Action.Torch -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("ni svetilke")
                cm.setTorchMode(id, action.on)
                if (action.on) "Svetilka prižgana." else "Svetilka ugasnjena."
            }
        }
    } catch (e: SecurityException) {
        "Manjka dovoljenje za to dejanje."
    } catch (e: Exception) {
        "Tega mi ni uspelo: ${e.message ?: "neznana napaka"}."
    }

    fun hasNotificationAccess(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    private fun controller(ctx: Context): MediaController? = runCatching {
        if (!hasNotificationAccess(ctx)) return null
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, NotifListener::class.java))
            .firstOrNull()
    }.getOrNull()

    private fun sendKey(ctx: Context, code: Int) {
        val am = audio(ctx)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun audio(ctx: Context): AudioManager = ctx.getSystemService(AudioManager::class.java)
}
