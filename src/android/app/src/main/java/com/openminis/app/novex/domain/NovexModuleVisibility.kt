package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleType

/** Private role material is selected by use before reading, searching or summarizing its text. */
object NovexModuleVisibility {
    fun isPlayerIdentity(type: ContentModuleType) = type == ContentModuleType.ROLE_PLAYER_IDENTITY ||
        type == ContentModuleType.GAME_PLAYER_IDENTITY

    fun isPrivate(type: ContentModuleType) = isPlayerIdentity(type) || type == ContentModuleType.ROLE_INSTRUCTIONS ||
        type == ContentModuleType.GAME_ANSWER_IDENTITY

    fun allowsContext(type: ContentModuleType, acting: Boolean): Boolean =
        !isPlayerIdentity(type) && type != ContentModuleType.GAME_ANSWER_IDENTITY &&
            (type != ContentModuleType.ROLE_INSTRUCTIONS || acting)
}
