package io.github.quiltservertools.blockbotdiscord.extensions

import dev.kordex.core.checks.inGuild
import dev.kordex.core.checks.isNotBot
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.utils.ensureWebhook
import dev.kordex.core.utils.getTopRole
import dev.kordex.core.utils.hasPermission
import com.vdurmont.emoji.EmojiParser
import dev.kord.common.entity.AllowedMentionType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.execute
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Webhook
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.builder.message.EmbedBuilder
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializer
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializerOptions
import dev.vankka.mcdiscordreserializer.minecraft.MinecraftSerializer
import dev.vankka.mcdiscordreserializer.minecraft.MinecraftSerializerOptions
import io.github.quiltservertools.blockbotapi.Bot
import io.github.quiltservertools.blockbotapi.Channels
import io.github.quiltservertools.blockbotapi.event.RelayMessageEvent
import io.github.quiltservertools.blockbotapi.sender.MessageSender
import io.github.quiltservertools.blockbotapi.sender.PlayerMessageSender
import io.github.quiltservertools.blockbotapi.sender.RelayMessageSender
import io.github.quiltservertools.blockbotdiscord.BlockBotDiscord
import io.github.quiltservertools.blockbotdiscord.MentionToMinecraftRenderer
import io.github.quiltservertools.blockbotdiscord.logInfo
import io.github.quiltservertools.blockbotdiscord.logWarn
import io.github.quiltservertools.blockbotdiscord.config.*
import io.github.quiltservertools.blockbotdiscord.translation.DiscordPackEmojis
import io.github.quiltservertools.blockbotdiscord.translation.ResourcePackEmojiText
import io.github.quiltservertools.blockbotdiscord.translation.advancementEmbed
import io.github.quiltservertools.blockbotdiscord.translation.withResourcePackTranslations
import io.github.quiltservertools.blockbotdiscord.utility.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.drex.vanish.api.VanishEvents
import net.fabricmc.loader.api.FabricLoader
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.TranslatableComponent
import net.minecraft.advancements.Advancement
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import org.koin.core.component.inject


class BlockBotApiExtension : Extension(), Bot {
    override val name = "BlockBot Api Impl"
    private lateinit var chatWebhook: Webhook

    private val minecraftSerializer = MinecraftSerializer(
        MinecraftSerializerOptions.defaults()
            .addRenderer(MentionToMinecraftRenderer(bot))
    )
    private val discordSerializer = DiscordSerializer(
        DiscordSerializerOptions.defaults()
            .withKeybindProvider { Component.translatable(it.keybind()).string }
            // The serializer expects substituted text and handles children separately.
            .withTranslationProvider { translate(it.children(emptyList()).toNative(server.registryAccess())) }
    )
    private val server: MinecraftServer by inject()
    private val mentions = AllowedMentionsBuilder()
    private val packEmojis by lazy {
        DiscordPackEmojis(
            kord,
            ::logWarn,
            ::logInfo,
            FabricLoader.getInstance().configDir.resolve("blockbot-resourcepack-cache/discord/${kord.selfId}/emoji-usage.json"),
        )
    }

    private fun launchWithPackEmojis(block: suspend () -> Unit) = BlockBotDiscord.launch {
        packEmojis.withMessage(block)
    }

    private fun emojiText(): ResourcePackEmojiText {
        val pack = BlockBotDiscord.resourcePackTranslations.snapshot
        return ResourcePackEmojiText(
            pack.translations,
            pack.glyphs,
            config[ResourcePackEmojisSpec.enabled] && config[ResourcePackEmojisSpec.coloured],
            ::logWarn,
        )
    }

    private suspend fun renderPackEmojis(
        text: String,
        maxLength: Int = 2000,
        glyphs: Map<Int, String> = BlockBotDiscord.resourcePackTranslations.glyphs,
        embed: Boolean = false,
    ): String =
        packEmojis.render(
            text,
            glyphs,
            maxLength,
            config[ResourcePackTranslationsSpec.enabled] && config[ResourcePackEmojisSpec.enabled],
            config[ResourcePackEmojisSpec.emojiPrefix],
            config[ResourcePackEmojisSpec.padding],
            config.resourcePackEmojiLayout(embed),
        )

    private fun translate(component: Component): String =
        component.withResourcePackTranslations(BlockBotDiscord.resourcePackTranslations.translations).string

    override suspend fun setup() {
        val (webhook, roleIds) = retryDiscordSetup(::logWarn) {
            val channel = config.getChannel(Channels.CHAT, bot)
            // On a retry, ensureWebhook first looks up an existing webhook.
            val webhook = ensureWebhook(channel as TopGuildMessageChannel, config[ChatRelaySpec.WebhookSpec.webhookName])
            val roleIds = config.getGuild(bot).roles.filter { it.mentionable }.map { it.id }.toList()
            webhook to roleIds
        }
        chatWebhook = webhook
        mentions.add(AllowedMentionType.UserMentions)
        mentions.roles.addAll(roleIds)

        event<MessageCreateEvent> {
            check { isNotBot() }
            check { inGuild(Snowflake(config[BotSpec.guild])) }
            check { failIfNot(config.getChannelsBi().containsValue(event.message.channelId.value)) }

            action {
                val sender = event.message.getAuthorAsMemberOrNull() ?: return@action
                val configChannel = config.getChannelsBi().inverse()[event.message.channelId.value]!!
                val result = RelayMessageEvent.EVENT.invoker().message(
                    RelayMessageSender(
                        sender.username,
                        sender.nickname,
                        sender.tag,
                        sender.hasPermission(Permission.Administrator)
                    ),
                    configChannel,
                    event.message.content
                )

                if (result == InteractionResult.PASS) {
                    if (configChannel == Channels.CHAT) {
                        val messages = getChatMessage(sender, event.message)

                        server.submit {
                            for (message in messages) {
                                server.playerList.broadcastSystemMessage(
                                    message,
                                    false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Vanish fake join/leave messages
        if (FabricLoader.getInstance().isModLoaded("melius-vanish")) {
            VanishEvents.VANISH_EVENT.register {player, vanished ->
                if (vanished) sendPlayerLeaveMessage(player)
                else sendPlayerJoinMessage(player)
            }
        }

        // Send server started message if the server has already started. isLoading should be named isRunning
        if (server.isReady) onServerStart(server)
    }

    public suspend fun getChatMessage(sender: Member, message: Message): List<Component> {
        val emojiString = EmojiParser.parseToAliases(message.content)
        var content: MutableComponent =
            if (config[ChatRelaySpec.convertMarkdown]) minecraftSerializer.serialize(emojiString)
                .toNative(server.registryAccess()) else emojiString.literal()
//        content = convertEmojiToTranslatable(content)
        if (message.referencedMessage != null) {
            val reply = config.getReplyMsg(
                message.referencedMessage!!.getAuthorAsMemberOrNull()?.effectiveName ?: message.referencedMessage!!.data.author.username,
                message.referencedMessage!!,
                server
            )
            content = "".literal().append(reply).append("\n").append(content)
        }

        val attachments = mutableListOf<Component>()

        if (message.attachments.isNotEmpty()) {
            val appendImages = config[ChatRelaySpec.MinecraftFormatSpec.appendImages]
            val interpolateImages = config[ChatRelaySpec.MinecraftFormatSpec.imageInterpolation]

            for (attachment in message.attachments) {
                attachments.add(attachment.toMinecraftMessage(appendImages, interpolateImages))
            }
        }

        for (stickerItem in message.stickers) {
            val sticker = stickerItem.getSticker()
            attachments.add("[Sticker: ${sticker.name}]".literal().withStyle {
                it.withHoverEvent(HoverEvent.ShowText((sticker.description ?: "").literal()))
            })
        }

        val topRole = sender.getTopRole()
        val topColor = sender.getDisplayColor()
        var topRoleMessage: MutableComponent =
            topRole?.data?.name?.literal() ?: "".literal()
        if (topColor != null) topRoleMessage = topRoleMessage.withStyle { it.withColor(topColor.rgb) }
        var username: MutableComponent = sender.effectiveName.literal()
        if (topColor != null) {
            username = username.withStyle {
                it.withColor(topColor.rgb)
            }
        }

        return listOf(
            config.getMinecraftChatRelayMsg(username, topRoleMessage, content, server),
            *attachments.toTypedArray()
        )
    }

    suspend fun createDiscordEmbed(builder: EmbedBuilder.() -> Unit) {
        createDiscordEmbed(EmbedBuilder().apply(builder))
    }

    private suspend fun createDiscordEmbed(embed: EmbedBuilder) {
        if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
            chatWebhook.execute(chatWebhook.token!!) {
                avatarUrl = config[ChatRelaySpec.WebhookSpec.webhookAvatar]
                allowedMentions = mentions
                embeds = mutableListOf(embed)
            }
        } else {
            val messageChannel = config.getChannel(Channels.CHAT, bot)
            messageChannel.createMessage {
                allowedMentions = mentions
                embeds = mutableListOf(embed)
            }
        }
    }

    suspend fun createDiscordMessage(content: String) = packEmojis.withMessage {
        val rendered = renderPackEmojis(content)
        if (rendered.isEmpty()) return@withMessage
        if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
            chatWebhook.execute(chatWebhook.token!!) {
                allowedMentions = mentions
                this.content = rendered
            }
        } else {
            val messageChannel = config.getChannel(Channels.CHAT, bot)
            messageChannel.createMessage {
                allowedMentions = mentions
                this.content = rendered
            }
        }
    }

    override fun onChatMessage(sender: MessageSender, message: Component) {
        launchWithPackEmojis {
            val emojiText = emojiText()
            var content = discordSerializer.serialize(
                emojiText.prepare(message).toAdventure(sender.wrapperLookup),
                discordSerializer.defaultOptions.withEscapeMarkdown(config[ChatRelaySpec.escapeIngameMarkdown])
            )
            if (config[ChatRelaySpec.allowMentions]) {
                content = convertStringToMention(content, config.getGuild(bot))
            }
            content = convertStringToEmoji(content, config.getGuild(bot))

            if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
                val rendered = renderPackEmojis(sender.formatWebhookContent(content), glyphs = emojiText.glyphs)
                if (rendered.isEmpty()) return@launchWithPackEmojis
                chatWebhook.execute(chatWebhook.token!!) {
                    this.allowedMentions = mentions
                    this.username = config.formatWebhookAuthor(sender)
                    this.content = rendered
                    this.avatarUrl = sender.getAvatar()
                }
            } else {
                val rendered = renderPackEmojis(sender.formatMessageContent(content), glyphs = emojiText.glyphs)
                if (rendered.isEmpty()) return@launchWithPackEmojis

                val messageChannel = config.getChannel(Channels.CHAT, bot)
                messageChannel.createMessage {
                    allowedMentions = mentions
                    this.content = rendered
                }
            }
        }
    }

    override fun onPlayerJoinMessage(player: ServerPlayer) {
        if (!player.isVanished()) sendPlayerJoinMessage(player)
    }

    private fun sendPlayerJoinMessage(player: ServerPlayer) {
        if (config.formatPlayerJoinMessage(player).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerJoinMessage(player)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.green
            }
        }
    }

    override fun onPlayerLeaveMessage(player: ServerPlayer) {
        if (!player.isVanished()) sendPlayerLeaveMessage(player)
    }

    private fun sendPlayerLeaveMessage(player: ServerPlayer) {
        if (config.formatPlayerLeaveMessage(player).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerLeaveMessage(player)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.red
            }
        }
    }

    override fun onPlayerDeath(player: ServerPlayer, message: Component) {
        if (config.formatPlayerDeathMessage(player, message, ::translate).isEmpty() || player.isVanished()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerDeathMessage(player, message, ::translate)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.orange
            }
        }
    }

    override fun onAdvancementGrant(player: ServerPlayer, advancement: Advancement) {
        if (config.formatPlayerAdvancementMessage(player, advancement, ::translate).isEmpty() || player.isVanished()) return
        launchWithPackEmojis {
            val emojiEnabled = config[ResourcePackEmojisSpec.enabled]
            val emojiText = emojiText()
            val title = config.formatPlayerAdvancementMessage(player, advancement, emojiText::translate)
            val details = emojiText.translate(advancement.display.get().description)
            val embed = advancementEmbed(
                if (emojiEnabled) title else DiscordPackEmojis.withoutGlyphs(title),
                if (emojiEnabled) details else DiscordPackEmojis.withoutGlyphs(details),
                config.getWebhookChatRelayAvatar(player.gameProfile),
            ) { renderPackEmojis(it, maxLength = 4096, glyphs = emojiText.glyphs, embed = true) }
            embed.color = Colors.blue
            createDiscordEmbed(embed)
        }
    }

    override fun onServerStart(server: MinecraftServer) {
        if (config.formatServerStartMessage(server).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                title = config.formatServerStartMessage(server)
                color = Colors.green
            }
        }
    }

    override fun onServerStop(server: MinecraftServer) {
        if (config.formatServerStopMessage(server).isEmpty()) return
        runBlocking {
            createDiscordEmbed {
                title = config.formatServerStopMessage(server)
                color = Colors.red
            }
            kord.shutdown()
        }
    }

    override fun onServerTick(server: MinecraftServer) {
        if (server.tickCount % 400 == 0) {
            BlockBotDiscord.launch {
                kord.editPresence {
                    when (config[PresenceSpec.activityType]) {
                        ActivityType.Game -> playing(config.formatPresenceText(server))
                        ActivityType.Listening -> listening(config.formatPresenceText(server))
                        ActivityType.Watching -> watching(config.formatPresenceText(server))
                        ActivityType.Competing -> competing(config.formatPresenceText(server))
                        ActivityType.Disabled -> Unit
                    }
                }
            }
        }
    }


    override fun sendRelayMessage(content: String, channel: String) {
    }

    override fun onRelayMessage(content: String, channel: String) {
    }
}

fun MessageSender.getAvatar(): String {
    return if (this is PlayerMessageSender) config.getWebhookChatRelayAvatar(this.profile)
    else config[ChatRelaySpec.WebhookSpec.webhookAvatar]
}

fun MessageSender.formatMessageContent(content: String): String {
    return when (this.type) {
        MessageSender.MessageType.REGULAR -> config.formatDiscordMessage(this, content)
        MessageSender.MessageType.EMOTE -> config.formatDiscordEmote(this, content)
        MessageSender.MessageType.ANNOUNCEMENT -> config.formatDiscordAnnouncement(this, content)
    }
}

fun MessageSender.formatWebhookContent(content: String): String {
    return when (this.type) {
        MessageSender.MessageType.REGULAR -> config.formatWebhookMessage(this, content)
        MessageSender.MessageType.EMOTE -> config.formatWebhookEmote(this, content)
        MessageSender.MessageType.ANNOUNCEMENT -> config.formatWebhookAnnouncement(this, content)
    }
}

suspend fun Member.getDisplayColor() =
    this.roles.toList().sortedByDescending { it.rawPosition }.firstOrNull { it.color.rgb != 0 }?.color
