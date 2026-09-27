// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

package xyz.superfunction.spfn.core.appcontract

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding

data class GetItemResponse(
    val item: GetItemResponseItem,
    val matrix: List<List<Long>>
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetItemResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetItemResponse(
                item = GetItemResponseItem.decode(members["item"] ?: SpfnCanonicalValue.Null, "$path.item"),
                matrix = SpfnDecoding.array(members["matrix"], "$path.matrix").map { value0 -> SpfnDecoding.array(value0, "$path.matrix").map { value1 -> SpfnDecoding.integer(value1, "$path.matrix") } }
            );
        }
    }
}

data class GetItemResponseItem(
    val archivedAt: String? = null,
    val default: String,
    val id: String,
    val `in`: Boolean,
    val kind: GetItemResponseItemKind,
    val labels: GetItemResponseItemLabels?,
    val lastEditor: GetItemResponseItemLastEditor?,
    val marker: GetItemResponseItemMarker,
    val note: String? = null,
    val owner: GetItemResponseItemOwner,
    val pinned: Boolean,
    val rank: Long,
    val reviewers: List<GetItemResponseItemReviewersItem>,
    val tags: List<String>,
    val title: String?,
    val updatedAt: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetItemResponseItem
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetItemResponseItem(
                archivedAt = FixtureAPISupport.nonNull(members["archivedAt"])?.let { value0 -> SpfnDecoding.string(value0, "$path.archivedAt") },
                default = SpfnDecoding.string(members["default"], "$path.default"),
                id = SpfnDecoding.string(members["id"], "$path.id"),
                `in` = SpfnDecoding.boolean(members["in"], "$path.in"),
                kind = GetItemResponseItemKind.of(SpfnDecoding.string(members["kind"], "$path.kind")),
                labels = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "labels", "$path.labels"))?.let { value0 -> GetItemResponseItemLabels.of(SpfnDecoding.string(value0, "$path.labels")) },
                lastEditor = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "lastEditor", "$path.lastEditor"))?.let { value0 -> GetItemResponseItemLastEditor.decode(value0, "$path.lastEditor") },
                marker = GetItemResponseItemMarker.of(SpfnDecoding.string(members["marker"], "$path.marker")),
                note = FixtureAPISupport.nonNull(members["note"])?.let { value0 -> SpfnDecoding.string(value0, "$path.note") },
                owner = GetItemResponseItemOwner.decode(members["owner"] ?: SpfnCanonicalValue.Null, "$path.owner"),
                pinned = SpfnDecoding.boolean(members["pinned"], "$path.pinned"),
                rank = SpfnDecoding.integer(members["rank"], "$path.rank"),
                reviewers = SpfnDecoding.array(members["reviewers"], "$path.reviewers").map { value0 -> GetItemResponseItemReviewersItem.decode(value0, "$path.reviewers") },
                tags = SpfnDecoding.array(members["tags"], "$path.tags").map { value0 -> SpfnDecoding.string(value0, "$path.tags") },
                title = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "title", "$path.title"))?.let { value0 -> SpfnDecoding.string(value0, "$path.title") },
                updatedAt = SpfnDecoding.string(members["updatedAt"], "$path.updatedAt")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface GetItemResponseItemKind
{
    val wireValue: String

    data object InReview : GetItemResponseItemKind
    {
        override val wireValue: String = "in_review"
    }

    data object Done : GetItemResponseItemKind
    {
        override val wireValue: String = "done"
    }

    data object Default : GetItemResponseItemKind
    {
        override val wireValue: String = "default"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : GetItemResponseItemKind

    companion object
    {
        fun of(wireValue: String): GetItemResponseItemKind = when (wireValue)
        {
            "in_review" -> InReview
            "done" -> Done
            "default" -> Default
            else -> Unknown(wireValue)
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface GetItemResponseItemLabels
{
    val wireValue: String

    data object Red : GetItemResponseItemLabels
    {
        override val wireValue: String = "red"
    }

    data object Blue : GetItemResponseItemLabels
    {
        override val wireValue: String = "blue"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : GetItemResponseItemLabels

    companion object
    {
        fun of(wireValue: String): GetItemResponseItemLabels = when (wireValue)
        {
            "red" -> Red
            "blue" -> Blue
            else -> Unknown(wireValue)
        }
    }
}

data class GetItemResponseItemLastEditor(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetItemResponseItemLastEditor
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetItemResponseItemLastEditor(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface GetItemResponseItemMarker
{
    val wireValue: String

    data object V1 : GetItemResponseItemMarker
    {
        override val wireValue: String = "v1"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : GetItemResponseItemMarker

    companion object
    {
        fun of(wireValue: String): GetItemResponseItemMarker = when (wireValue)
        {
            "v1" -> V1
            else -> Unknown(wireValue)
        }
    }
}

data class GetItemResponseItemOwner(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetItemResponseItemOwner
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetItemResponseItemOwner(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

data class GetItemResponseItemReviewersItem(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetItemResponseItemReviewersItem
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetItemResponseItemReviewersItem(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface ListItemsQuerySort
{
    val wireValue: String

    data object Newest : ListItemsQuerySort
    {
        override val wireValue: String = "newest"
    }

    data object Oldest : ListItemsQuerySort
    {
        override val wireValue: String = "oldest"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : ListItemsQuerySort

    companion object
    {
        fun of(wireValue: String): ListItemsQuerySort = when (wireValue)
        {
            "newest" -> Newest
            "oldest" -> Oldest
            else -> Unknown(wireValue)
        }
    }
}

data class ListItemsResponse(
    val items: List<ListItemsResponseItemsItem>,
    val nextCursor: String? = null
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): ListItemsResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return ListItemsResponse(
                items = SpfnDecoding.array(members["items"], "$path.items").map { value0 -> ListItemsResponseItemsItem.decode(value0, "$path.items") },
                nextCursor = FixtureAPISupport.nonNull(members["nextCursor"])?.let { value0 -> SpfnDecoding.string(value0, "$path.nextCursor") }
            );
        }
    }
}

data class ListItemsResponseItemsItem(
    val archivedAt: String? = null,
    val default: String,
    val id: String,
    val `in`: Boolean,
    val kind: ListItemsResponseItemsItemKind,
    val labels: ListItemsResponseItemsItemLabels?,
    val lastEditor: ListItemsResponseItemsItemLastEditor?,
    val marker: ListItemsResponseItemsItemMarker,
    val note: String? = null,
    val owner: ListItemsResponseItemsItemOwner,
    val pinned: Boolean,
    val rank: Long,
    val reviewers: List<ListItemsResponseItemsItemReviewersItem>,
    val tags: List<String>,
    val title: String?,
    val updatedAt: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): ListItemsResponseItemsItem
        {
            val members = SpfnDecoding.obj(canonical, path);
            return ListItemsResponseItemsItem(
                archivedAt = FixtureAPISupport.nonNull(members["archivedAt"])?.let { value0 -> SpfnDecoding.string(value0, "$path.archivedAt") },
                default = SpfnDecoding.string(members["default"], "$path.default"),
                id = SpfnDecoding.string(members["id"], "$path.id"),
                `in` = SpfnDecoding.boolean(members["in"], "$path.in"),
                kind = ListItemsResponseItemsItemKind.of(SpfnDecoding.string(members["kind"], "$path.kind")),
                labels = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "labels", "$path.labels"))?.let { value0 -> ListItemsResponseItemsItemLabels.of(SpfnDecoding.string(value0, "$path.labels")) },
                lastEditor = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "lastEditor", "$path.lastEditor"))?.let { value0 -> ListItemsResponseItemsItemLastEditor.decode(value0, "$path.lastEditor") },
                marker = ListItemsResponseItemsItemMarker.of(SpfnDecoding.string(members["marker"], "$path.marker")),
                note = FixtureAPISupport.nonNull(members["note"])?.let { value0 -> SpfnDecoding.string(value0, "$path.note") },
                owner = ListItemsResponseItemsItemOwner.decode(members["owner"] ?: SpfnCanonicalValue.Null, "$path.owner"),
                pinned = SpfnDecoding.boolean(members["pinned"], "$path.pinned"),
                rank = SpfnDecoding.integer(members["rank"], "$path.rank"),
                reviewers = SpfnDecoding.array(members["reviewers"], "$path.reviewers").map { value0 -> ListItemsResponseItemsItemReviewersItem.decode(value0, "$path.reviewers") },
                tags = SpfnDecoding.array(members["tags"], "$path.tags").map { value0 -> SpfnDecoding.string(value0, "$path.tags") },
                title = FixtureAPISupport.nonNull(FixtureAPISupport.present(members, "title", "$path.title"))?.let { value0 -> SpfnDecoding.string(value0, "$path.title") },
                updatedAt = SpfnDecoding.string(members["updatedAt"], "$path.updatedAt")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface ListItemsResponseItemsItemKind
{
    val wireValue: String

    data object InReview : ListItemsResponseItemsItemKind
    {
        override val wireValue: String = "in_review"
    }

    data object Done : ListItemsResponseItemsItemKind
    {
        override val wireValue: String = "done"
    }

    data object Default : ListItemsResponseItemsItemKind
    {
        override val wireValue: String = "default"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : ListItemsResponseItemsItemKind

    companion object
    {
        fun of(wireValue: String): ListItemsResponseItemsItemKind = when (wireValue)
        {
            "in_review" -> InReview
            "done" -> Done
            "default" -> Default
            else -> Unknown(wireValue)
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface ListItemsResponseItemsItemLabels
{
    val wireValue: String

    data object Red : ListItemsResponseItemsItemLabels
    {
        override val wireValue: String = "red"
    }

    data object Blue : ListItemsResponseItemsItemLabels
    {
        override val wireValue: String = "blue"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : ListItemsResponseItemsItemLabels

    companion object
    {
        fun of(wireValue: String): ListItemsResponseItemsItemLabels = when (wireValue)
        {
            "red" -> Red
            "blue" -> Blue
            else -> Unknown(wireValue)
        }
    }
}

data class ListItemsResponseItemsItemLastEditor(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): ListItemsResponseItemsItemLastEditor
        {
            val members = SpfnDecoding.obj(canonical, path);
            return ListItemsResponseItemsItemLastEditor(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface ListItemsResponseItemsItemMarker
{
    val wireValue: String

    data object V1 : ListItemsResponseItemsItemMarker
    {
        override val wireValue: String = "v1"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : ListItemsResponseItemsItemMarker

    companion object
    {
        fun of(wireValue: String): ListItemsResponseItemsItemMarker = when (wireValue)
        {
            "v1" -> V1
            else -> Unknown(wireValue)
        }
    }
}

data class ListItemsResponseItemsItemOwner(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): ListItemsResponseItemsItemOwner
        {
            val members = SpfnDecoding.obj(canonical, path);
            return ListItemsResponseItemsItemOwner(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

data class ListItemsResponseItemsItemReviewersItem(
    val displayName: String,
    val id: String
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): ListItemsResponseItemsItemReviewersItem
        {
            val members = SpfnDecoding.obj(canonical, path);
            return ListItemsResponseItemsItemReviewersItem(
                displayName = SpfnDecoding.string(members["displayName"], "$path.displayName"),
                id = SpfnDecoding.string(members["id"], "$path.id")
            );
        }
    }
}

data class PutItemNoteBody(
    val attachments: List<PutItemNoteBodyAttachmentsItem>,
    val label: String?,
    val mode: PutItemNoteBodyMode,
    val pinned: Boolean? = null,
    val position: PutItemNoteBodyPosition? = null,
    val text: String
)
{
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["attachments"] = SpfnCanonicalValue.Arr(this.attachments.map { value0 -> value0.canonicalValue() });
        members["label"] = this.label?.let { value0 -> SpfnCanonicalValue.Text(value0) } ?: SpfnCanonicalValue.Null;
        members["mode"] = SpfnCanonicalValue.Text(this.mode.wireValue);
        this.pinned?.let { value0 -> members["pinned"] = SpfnCanonicalValue.Bool(value0) };
        this.position?.let { value0 -> members["position"] = value0.canonicalValue() };
        members["text"] = SpfnCanonicalValue.Text(this.text);
        return SpfnCanonicalValue.Obj(members);
    }
}

data class PutItemNoteBodyAttachmentsItem(
    val name: String,
    val `object`: Boolean? = null,
    val size: Long
)
{
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["name"] = SpfnCanonicalValue.Text(this.name);
        this.`object`?.let { value0 -> members["object"] = SpfnCanonicalValue.Bool(value0) };
        members["size"] = SpfnCanonicalValue.Integer(this.size);
        return SpfnCanonicalValue.Obj(members);
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface PutItemNoteBodyMode
{
    val wireValue: String

    data object Append : PutItemNoteBodyMode
    {
        override val wireValue: String = "append"
    }

    data object Replace : PutItemNoteBodyMode
    {
        override val wireValue: String = "replace"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : PutItemNoteBodyMode

    companion object
    {
        fun of(wireValue: String): PutItemNoteBodyMode = when (wireValue)
        {
            "append" -> Append
            "replace" -> Replace
            else -> Unknown(wireValue)
        }
    }
}

data class PutItemNoteBodyPosition(
    val column: Long,
    val line: Long
)
{
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["column"] = SpfnCanonicalValue.Integer(this.column);
        members["line"] = SpfnCanonicalValue.Integer(this.line);
        return SpfnCanonicalValue.Obj(members);
    }
}
