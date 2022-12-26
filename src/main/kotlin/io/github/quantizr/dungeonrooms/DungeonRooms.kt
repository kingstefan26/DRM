/*
 * Dungeon Rooms Mod - Secret Waypoints for Hypixel Skyblock Dungeons
 * Copyright 2021 Quantizr(_risk)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.quantizr.dungeonrooms

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.quantizr.dungeonrooms.commands.RoomCommand
import io.github.quantizr.dungeonrooms.dungeons.DungeonManager
import io.github.quantizr.dungeonrooms.dungeons.RoomDetection
import io.github.quantizr.dungeonrooms.dungeons.Waypoints
import io.github.quantizr.dungeonrooms.gui.WaypointsGUI
import io.github.quantizr.dungeonrooms.handlers.ConfigHandler.reloadConfig
import io.github.quantizr.dungeonrooms.handlers.OpenLink.checkForLink
import io.github.quantizr.dungeonrooms.handlers.PacketHandler
import io.github.quantizr.dungeonrooms.handlers.TextRenderer.drawText
import io.github.quantizr.dungeonrooms.utils.Utils
import io.github.quantizr.dungeonrooms.utils.Utils.checkForCatacombs
import io.github.quantizr.dungeonrooms.utils.Utils.checkForSkyblock
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.event.ClickEvent
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumChatFormatting
import net.minecraftforge.client.ClientCommandHandler
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Loader
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent
import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer

@Mod(modid = DungeonRooms.MODID, version = DungeonRooms.VERSION)
class DungeonRooms {
    var mc: Minecraft = Minecraft.getMinecraft()

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        ClientCommandHandler.instance.registerCommand(RoomCommand())
        configDir = event.modConfigurationDirectory.toString()

        //initialize logger
        logger = LogManager.getLogger(instance::class.java)
//        Utils.setLogLevel(LogManager.getLogger(DungeonRooms::class.java), Level.DEBUG)
    }

    val roomDetection = RoomDetection()
    val roomDataLoader = RoomDataLoader()
    val dungeonManager = DungeonManager()

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent?) {

        DRMConfig.init()
        roomDataLoader.startAsyncLoad()

        //register classes
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(ChatTransmitter())
        MinecraftForge.EVENT_BUS.register(dungeonManager)
        MinecraftForge.EVENT_BUS.register(roomDetection)
        MinecraftForge.EVENT_BUS.register(Waypoints())

        //reload config
        reloadConfig()

        roomDataLoader.blockTillLoad()

    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent?) {
        usingSBPSecrets = Loader.isModLoaded("sbp")
        logger.info("DungeonRooms: SBP Dungeon Secrets detection: $usingSBPSecrets")
    }

    fun forEverySecretInRoom(comsumer: Consumer<Pair<JsonObject, String>>) {
        val (secretsArray, roomName) = getSecretsArray() ?: return

        for (i in 0 until secretsArray.size()) {
            val secret = secretsArray[i].asJsonObject
            comsumer.accept(Pair(secret, roomName))
        }

    }

    fun getSecretsObject(): Pair<JsonObject, String>? {
        if(roomDetection.roomName == "undefined") return null

        val arr = roomDataLoader.waypointsJson[roomDetection.roomName] ?: return null

        return Pair(arr.asJsonObject, roomDetection.roomName)
    }
    
    fun getSecretsArray(): Pair<JsonArray, String>? {
        if(roomDetection.roomName == "undefined") return null

        val arr = roomDataLoader.waypointsJson[roomDetection.roomName] ?: return null

        return Pair(arr.asJsonArray, roomDetection.roomName)
    }

    /**
     * Modified from Danker's Skyblock Mod under the GNU General Public License v3.0
     * https://github.com/bowser0000/SkyblockMod/blob/master/LICENSE
     * @author bowser0000
     */
    @SubscribeEvent
    fun onServerConnect(event: ClientConnectedToServerEvent) {
        if (mc.currentServerData == null) return
        if (mc.currentServerData.serverIP.lowercase().contains("hypixel.")) {
            logger.info("DungeonRooms: Connecting to Hypixel...")

            //Packets are used in this mod solely to detect when the player picks up an item. No packets are modified or created.
            event.manager.channel().pipeline().addBefore("packet_handler", "drm_packet_handler", PacketHandler())
            logger.info("DungeonRooms: Packet Handler added")
            Thread {
                try {
                    while (mc.thePlayer == null) {
                        //Yes, I'm too lazy to code something proper, so I'm busy-waiting, shut up. no :) -carmel
                        //It usually waits for less than half a second
                        Thread.sleep(100)
                    }
                    Thread.sleep(3000)
                    if (mc.currentServerData.serverIP.lowercase().contains("hypixel.")) {
                        logger.info("DungeonRooms: Checking for updates...")
                        var url = URL("https://api.github.com/repos/Quantizr/DungeonRoomsMod/releases/latest")
                        val request = url.openConnection()
                        request.connect()
                        val json = JsonParser()
                        val latestRelease = json.parse(InputStreamReader(request.content as InputStream)).asJsonObject
                        val latestTag = latestRelease["tag_name"].asString
                        val currentVersion = DefaultArtifactVersion(VERSION)
                        val latestVersion = DefaultArtifactVersion(latestTag.substring(1))
                        if (currentVersion < latestVersion) {
                            val releaseURL = "https://github.com/Quantizr/DungeonRoomsMod/releases/latest"
                            val update =
                                ChatComponentText("${EnumChatFormatting.GREEN}${EnumChatFormatting.BOLD}  [UPDATE]  ")
                            update.chatStyle =
                                update.chatStyle.setChatClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, releaseURL))
                            mc.thePlayer.addChatMessage(
                                ChatComponentText(
                                    """
                                    ${EnumChatFormatting.RED}Dungeon Rooms Mod is outdated. Please update to $latestTag.
                                    """.trimIndent()
                                ).appendSibling(update)
                            )
                        } else {
                            logger.info("DungeonRooms: No update found")
                        }
                        logger.info("DungeonRooms: Getting MOTD...")
                        url = URL("https://gist.githubusercontent.com/Quantizr/01aca53e61cef5dfd08989fec600b204/raw/")
                        val `in` = BufferedReader(InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
                        var line: String
                        motd = ArrayList()
                        while (`in`.readLine().also { line = it } != null) {
                            (motd as ArrayList<String>).add(line)
                        }
                        `in`.close()
                        logger.info("DungeonRooms: MOTD has been checked")
                    }
                } catch (e: IOException) {
                    mc.thePlayer.addChatMessage(ChatComponentText(EnumChatFormatting.RED.toString() + "Dungeon Rooms: An error has occured. See logs for more details."))
                    e.printStackTrace()
                } catch (e: InterruptedException) {
                    mc.thePlayer.addChatMessage(ChatComponentText(EnumChatFormatting.RED.toString() + "Dungeon Rooms: An error has occured. See logs for more details."))
                    e.printStackTrace()
                }
            }.start()
        }
    }

    @SubscribeEvent
    fun onTick(event: ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        val player = mc.thePlayer
        tickAmount++
        if (tickAmount % 20 == 0) {
            if (player != null) {
                checkForSkyblock()
                checkForCatacombs()
                tickAmount = 0
            }
        }
    }

    @SubscribeEvent
    fun onKey(event: InputEvent.KeyInputEvent?) {
        if (DRMConfig.openSecretImages.isActive) {
            if (!Utils.inCatacombs) {
                ChatTransmitter.addToQueue("${EnumChatFormatting.RED}Dungeon Rooms: Use this hotkey inside of a dungeon room")
                return
            }
            when (DRMConfig.imageHotkeyOpen) {
                0 -> checkForLink("gui")
                1 -> checkForLink("dsg")
                2 -> checkForLink("sbp")
                else -> ChatTransmitter.addToQueue("${EnumChatFormatting.RED}Dungeon Rooms: hotkeyOpen config value improperly set, do \"/room set <gui | dsg | sbp>\" to change the value")
            }
        }
        if (DRMConfig.waypointGuiKey.isActive) {
            mc.addScheduledTask { mc.displayGuiScreen(WaypointsGUI()) }
        }
        if (DRMConfig.practiceModeKeyBind.isActive) {
            if (DRMConfig.waypointsEnabled && !DRMConfig.practiceModeOn) {
                ChatTransmitter.addToQueue("""${EnumChatFormatting.RED}Dungeon Rooms: Run "/room toggle practice" to enable Practice Mode.""")

            } else if (!DRMConfig.waypointsEnabled && DRMConfig.practiceModeOn) {
                ChatTransmitter.addToQueue("${EnumChatFormatting.RED}Dungeon Rooms: Waypoints must be enabled for Practice Mode to work.")

            }
        }
    }

    @SubscribeEvent
    fun renderPlayerInfo(event: RenderGameOverlayEvent.Post) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return
        if (!Utils.inSkyblock) return
        if (textToDisplay != null) {
            if (textToDisplay!!.isNotEmpty()) {
                val scaledResolution = ScaledResolution(mc)
                var y = 0
                for (line in textToDisplay!!) {
                    val roomStringWidth = mc.fontRendererObj.getStringWidth(line)
                    drawText(
                        mc, line, scaledResolution.scaledWidth * DRMConfig.textLocX / 100 - roomStringWidth / 2,
                        scaledResolution.scaledHeight * DRMConfig.textLocY / 100 + y, 1.0, true
                    )
                    y += mc.fontRendererObj.FONT_HEIGHT
                }
            }
        }
    }

    companion object {
        const val debug: Boolean = false

        @Mod.Instance
        @JvmStatic
        lateinit var instance: DungeonRooms
            private set

        const val MODID = "@ID@"
        const val VERSION = "@VER@"
        lateinit var logger: Logger


        var usingSBPSecrets = false
        var tickAmount = 1
        var textToDisplay: List<String>? = null
        var motd: MutableList<String>? = null
        var configDir: String? = null
        var firstLogin = false
    }
}