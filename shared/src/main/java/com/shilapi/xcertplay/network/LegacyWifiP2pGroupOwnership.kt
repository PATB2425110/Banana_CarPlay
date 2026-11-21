package com.shilapi.xcertplay.network

/** Only remove a group when its observed identity matches the group this manager owns. */
internal fun legacyP2pGroupCanBeRemoved(
    isGroupOwner: Boolean,
    observedGroupName: String?,
    ownedGroupName: String?,
): Boolean = isGroupOwner && !ownedGroupName.isNullOrBlank() && observedGroupName == ownedGroupName
