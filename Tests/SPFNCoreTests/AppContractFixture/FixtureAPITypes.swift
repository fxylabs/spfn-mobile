// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

import SPFNCore

extension FixtureAPI
{
    public struct GetItemResponse: Equatable, Sendable
    {
        public var item: GetItemResponseItem
        public var matrix: [[Int64]]

        public init(
            item: GetItemResponseItem,
            matrix: [[Int64]]
        )
        {
            self.item = item
            self.matrix = matrix
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.item = try GetItemResponseItem(canonical: members["item"] ?? .null, at: "\(path).item")
            self.matrix = try SPFNDecoding.array(members["matrix"], at: "\(path).matrix").map { try SPFNDecoding.array($0, at: "\(path).matrix").map { try SPFNDecoding.integer($0, at: "\(path).matrix") } }
        }
    }

    public struct GetItemResponseItem: Equatable, Sendable
    {
        public var archivedAt: String?
        public var `default`: String
        public var id: String
        public var `in`: Bool
        public var kind: GetItemResponseItemKind
        public var labels: GetItemResponseItemLabels?
        public var lastEditor: GetItemResponseItemLastEditor?
        public var marker: GetItemResponseItemMarker
        public var note: String?
        public var owner: GetItemResponseItemOwner
        public var pinned: Bool
        public var rank: Int64
        public var reviewers: [GetItemResponseItemReviewersItem]
        public var tags: [String]
        public var title: String?
        public var updatedAt: String

        public init(
            archivedAt: String? = nil,
            `default`: String,
            id: String,
            `in`: Bool,
            kind: GetItemResponseItemKind,
            labels: GetItemResponseItemLabels?,
            lastEditor: GetItemResponseItemLastEditor?,
            marker: GetItemResponseItemMarker,
            note: String? = nil,
            owner: GetItemResponseItemOwner,
            pinned: Bool,
            rank: Int64,
            reviewers: [GetItemResponseItemReviewersItem],
            tags: [String],
            title: String?,
            updatedAt: String
        )
        {
            self.archivedAt = archivedAt
            self.`default` = `default`
            self.id = id
            self.`in` = `in`
            self.kind = kind
            self.labels = labels
            self.lastEditor = lastEditor
            self.marker = marker
            self.note = note
            self.owner = owner
            self.pinned = pinned
            self.rank = rank
            self.reviewers = reviewers
            self.tags = tags
            self.title = title
            self.updatedAt = updatedAt
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.archivedAt = try FixtureAPI.nonNull(members["archivedAt"]).map { try SPFNDecoding.string($0, at: "\(path).archivedAt") }
            self.`default` = try SPFNDecoding.string(members["default"], at: "\(path).default")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
            self.`in` = try SPFNDecoding.boolean(members["in"], at: "\(path).in")
            self.kind = try GetItemResponseItemKind(wireValue: SPFNDecoding.string(members["kind"], at: "\(path).kind"))
            self.labels = try FixtureAPI.nonNull(FixtureAPI.present(members, "labels", at: "\(path).labels")).map { try GetItemResponseItemLabels(wireValue: SPFNDecoding.string($0, at: "\(path).labels")) }
            self.lastEditor = try FixtureAPI.nonNull(FixtureAPI.present(members, "lastEditor", at: "\(path).lastEditor")).map { try GetItemResponseItemLastEditor(canonical: $0, at: "\(path).lastEditor") }
            self.marker = try GetItemResponseItemMarker(wireValue: SPFNDecoding.string(members["marker"], at: "\(path).marker"))
            self.note = try FixtureAPI.nonNull(members["note"]).map { try SPFNDecoding.string($0, at: "\(path).note") }
            self.owner = try GetItemResponseItemOwner(canonical: members["owner"] ?? .null, at: "\(path).owner")
            self.pinned = try SPFNDecoding.boolean(members["pinned"], at: "\(path).pinned")
            self.rank = try SPFNDecoding.integer(members["rank"], at: "\(path).rank")
            self.reviewers = try SPFNDecoding.array(members["reviewers"], at: "\(path).reviewers").map { try GetItemResponseItemReviewersItem(canonical: $0, at: "\(path).reviewers") }
            self.tags = try SPFNDecoding.array(members["tags"], at: "\(path).tags").map { try SPFNDecoding.string($0, at: "\(path).tags") }
            self.title = try FixtureAPI.nonNull(FixtureAPI.present(members, "title", at: "\(path).title")).map { try SPFNDecoding.string($0, at: "\(path).title") }
            self.updatedAt = try SPFNDecoding.string(members["updatedAt"], at: "\(path).updatedAt")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum GetItemResponseItemKind: Hashable, Sendable
    {
        case inReview
        case done
        case `default`
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "in_review":
                self = .inReview
            case "done":
                self = .done
            case "default":
                self = .`default`
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .inReview:
                return "in_review"
            case .done:
                return "done"
            case .`default`:
                return "default"
            case .unknown(let value):
                return value
            }
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum GetItemResponseItemLabels: Hashable, Sendable
    {
        case red
        case blue
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "red":
                self = .red
            case "blue":
                self = .blue
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .red:
                return "red"
            case .blue:
                return "blue"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct GetItemResponseItemLastEditor: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum GetItemResponseItemMarker: Hashable, Sendable
    {
        case v1
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "v1":
                self = .v1
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .v1:
                return "v1"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct GetItemResponseItemOwner: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    public struct GetItemResponseItemReviewersItem: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum ListItemsQuerySort: Hashable, Sendable
    {
        case newest
        case oldest
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "newest":
                self = .newest
            case "oldest":
                self = .oldest
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .newest:
                return "newest"
            case .oldest:
                return "oldest"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct ListItemsResponse: Equatable, Sendable
    {
        public var items: [ListItemsResponseItemsItem]
        public var nextCursor: String?

        public init(
            items: [ListItemsResponseItemsItem],
            nextCursor: String? = nil
        )
        {
            self.items = items
            self.nextCursor = nextCursor
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.items = try SPFNDecoding.array(members["items"], at: "\(path).items").map { try ListItemsResponseItemsItem(canonical: $0, at: "\(path).items") }
            self.nextCursor = try FixtureAPI.nonNull(members["nextCursor"]).map { try SPFNDecoding.string($0, at: "\(path).nextCursor") }
        }
    }

    public struct ListItemsResponseItemsItem: Equatable, Sendable
    {
        public var archivedAt: String?
        public var `default`: String
        public var id: String
        public var `in`: Bool
        public var kind: ListItemsResponseItemsItemKind
        public var labels: ListItemsResponseItemsItemLabels?
        public var lastEditor: ListItemsResponseItemsItemLastEditor?
        public var marker: ListItemsResponseItemsItemMarker
        public var note: String?
        public var owner: ListItemsResponseItemsItemOwner
        public var pinned: Bool
        public var rank: Int64
        public var reviewers: [ListItemsResponseItemsItemReviewersItem]
        public var tags: [String]
        public var title: String?
        public var updatedAt: String

        public init(
            archivedAt: String? = nil,
            `default`: String,
            id: String,
            `in`: Bool,
            kind: ListItemsResponseItemsItemKind,
            labels: ListItemsResponseItemsItemLabels?,
            lastEditor: ListItemsResponseItemsItemLastEditor?,
            marker: ListItemsResponseItemsItemMarker,
            note: String? = nil,
            owner: ListItemsResponseItemsItemOwner,
            pinned: Bool,
            rank: Int64,
            reviewers: [ListItemsResponseItemsItemReviewersItem],
            tags: [String],
            title: String?,
            updatedAt: String
        )
        {
            self.archivedAt = archivedAt
            self.`default` = `default`
            self.id = id
            self.`in` = `in`
            self.kind = kind
            self.labels = labels
            self.lastEditor = lastEditor
            self.marker = marker
            self.note = note
            self.owner = owner
            self.pinned = pinned
            self.rank = rank
            self.reviewers = reviewers
            self.tags = tags
            self.title = title
            self.updatedAt = updatedAt
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.archivedAt = try FixtureAPI.nonNull(members["archivedAt"]).map { try SPFNDecoding.string($0, at: "\(path).archivedAt") }
            self.`default` = try SPFNDecoding.string(members["default"], at: "\(path).default")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
            self.`in` = try SPFNDecoding.boolean(members["in"], at: "\(path).in")
            self.kind = try ListItemsResponseItemsItemKind(wireValue: SPFNDecoding.string(members["kind"], at: "\(path).kind"))
            self.labels = try FixtureAPI.nonNull(FixtureAPI.present(members, "labels", at: "\(path).labels")).map { try ListItemsResponseItemsItemLabels(wireValue: SPFNDecoding.string($0, at: "\(path).labels")) }
            self.lastEditor = try FixtureAPI.nonNull(FixtureAPI.present(members, "lastEditor", at: "\(path).lastEditor")).map { try ListItemsResponseItemsItemLastEditor(canonical: $0, at: "\(path).lastEditor") }
            self.marker = try ListItemsResponseItemsItemMarker(wireValue: SPFNDecoding.string(members["marker"], at: "\(path).marker"))
            self.note = try FixtureAPI.nonNull(members["note"]).map { try SPFNDecoding.string($0, at: "\(path).note") }
            self.owner = try ListItemsResponseItemsItemOwner(canonical: members["owner"] ?? .null, at: "\(path).owner")
            self.pinned = try SPFNDecoding.boolean(members["pinned"], at: "\(path).pinned")
            self.rank = try SPFNDecoding.integer(members["rank"], at: "\(path).rank")
            self.reviewers = try SPFNDecoding.array(members["reviewers"], at: "\(path).reviewers").map { try ListItemsResponseItemsItemReviewersItem(canonical: $0, at: "\(path).reviewers") }
            self.tags = try SPFNDecoding.array(members["tags"], at: "\(path).tags").map { try SPFNDecoding.string($0, at: "\(path).tags") }
            self.title = try FixtureAPI.nonNull(FixtureAPI.present(members, "title", at: "\(path).title")).map { try SPFNDecoding.string($0, at: "\(path).title") }
            self.updatedAt = try SPFNDecoding.string(members["updatedAt"], at: "\(path).updatedAt")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum ListItemsResponseItemsItemKind: Hashable, Sendable
    {
        case inReview
        case done
        case `default`
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "in_review":
                self = .inReview
            case "done":
                self = .done
            case "default":
                self = .`default`
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .inReview:
                return "in_review"
            case .done:
                return "done"
            case .`default`:
                return "default"
            case .unknown(let value):
                return value
            }
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum ListItemsResponseItemsItemLabels: Hashable, Sendable
    {
        case red
        case blue
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "red":
                self = .red
            case "blue":
                self = .blue
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .red:
                return "red"
            case .blue:
                return "blue"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct ListItemsResponseItemsItemLastEditor: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum ListItemsResponseItemsItemMarker: Hashable, Sendable
    {
        case v1
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "v1":
                self = .v1
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .v1:
                return "v1"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct ListItemsResponseItemsItemOwner: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    public struct ListItemsResponseItemsItemReviewersItem: Equatable, Sendable
    {
        public var displayName: String
        public var id: String

        public init(
            displayName: String,
            id: String
        )
        {
            self.displayName = displayName
            self.id = id
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.displayName = try SPFNDecoding.string(members["displayName"], at: "\(path).displayName")
            self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        }
    }

    public struct PutItemNoteBody: Equatable, Sendable
    {
        public var attachments: [PutItemNoteBodyAttachmentsItem]
        public var label: String?
        public var mode: PutItemNoteBodyMode
        public var pinned: Bool?
        public var position: PutItemNoteBodyPosition?
        public var text: String

        public init(
            attachments: [PutItemNoteBodyAttachmentsItem],
            label: String?,
            mode: PutItemNoteBodyMode,
            pinned: Bool? = nil,
            position: PutItemNoteBodyPosition? = nil,
            text: String
        )
        {
            self.attachments = attachments
            self.label = label
            self.mode = mode
            self.pinned = pinned
            self.position = position
            self.text = text
        }

        public func canonicalValue() -> SPFNCanonicalValue
        {
            var members: [String: SPFNCanonicalValue] = [:]
            members["attachments"] = .array(self.attachments.map { $0.canonicalValue() })
            if let value = self.label
            {
                members["label"] = .string(value)
            }
            else
            {
                members["label"] = .null
            }
            members["mode"] = .string(self.mode.wireValue)
            if let value = self.pinned
            {
                members["pinned"] = .bool(value)
            }
            if let value = self.position
            {
                members["position"] = value.canonicalValue()
            }
            members["text"] = .string(self.text)
            return .object(members)
        }
    }

    public struct PutItemNoteBodyAttachmentsItem: Equatable, Sendable
    {
        public var name: String
        public var object: Bool?
        public var size: Int64

        public init(
            name: String,
            object: Bool? = nil,
            size: Int64
        )
        {
            self.name = name
            self.object = object
            self.size = size
        }

        public func canonicalValue() -> SPFNCanonicalValue
        {
            var members: [String: SPFNCanonicalValue] = [:]
            members["name"] = .string(self.name)
            if let value = self.object
            {
                members["object"] = .bool(value)
            }
            members["size"] = .integer(self.size)
            return .object(members)
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum PutItemNoteBodyMode: Hashable, Sendable
    {
        case append
        case replace
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "append":
                self = .append
            case "replace":
                self = .replace
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .append:
                return "append"
            case .replace:
                return "replace"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct PutItemNoteBodyPosition: Equatable, Sendable
    {
        public var column: Int64
        public var line: Int64

        public init(
            column: Int64,
            line: Int64
        )
        {
            self.column = column
            self.line = line
        }

        public func canonicalValue() -> SPFNCanonicalValue
        {
            var members: [String: SPFNCanonicalValue] = [:]
            members["column"] = .integer(self.column)
            members["line"] = .integer(self.line)
            return .object(members)
        }
    }
}
