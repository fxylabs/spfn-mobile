// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.v1.json
// bundleSha256:    07fd82683576e3343753b590e00b5bf9725b2e598e1e5e6282f251e73a433e45
// contractVersion: 1.0.0-dev.1
// origin:          spfn-mobile-step2-dev-bundle
//
// The bundle was hand-authored in this repository and was NOT exported by SPFN primitives CI.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

public struct SPFNHandshakeRequest: Equatable, Sendable
{
    public var clientId: String
    public var keyId: String
    public var nonce: String
    public var issuedAtMillis: Int64

    public init(
        clientId: String,
        keyId: String,
        nonce: String,
        issuedAtMillis: Int64
    )
    {
        self.clientId = clientId
        self.keyId = keyId
        self.nonce = nonce
        self.issuedAtMillis = issuedAtMillis
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["clientId"] = .string(clientId)
        members["keyId"] = .string(keyId)
        members["nonce"] = .string(nonce)
        members["issuedAtMillis"] = .integer(issuedAtMillis)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.clientId = try SPFNDecoding.string(members["clientId"], at: "\(path).clientId")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.nonce = try SPFNDecoding.string(members["nonce"], at: "\(path).nonce")
        self.issuedAtMillis = try SPFNDecoding.integer(members["issuedAtMillis"], at: "\(path).issuedAtMillis")
    }
}

public struct SPFNHandshakeResponse: Equatable, Sendable
{
    public var sessionId: String
    public var expiresAtMillis: Int64

    public init(
        sessionId: String,
        expiresAtMillis: Int64
    )
    {
        self.sessionId = sessionId
        self.expiresAtMillis = expiresAtMillis
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["sessionId"] = .string(sessionId)
        members["expiresAtMillis"] = .integer(expiresAtMillis)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.sessionId = try SPFNDecoding.string(members["sessionId"], at: "\(path).sessionId")
        self.expiresAtMillis = try SPFNDecoding.integer(members["expiresAtMillis"], at: "\(path).expiresAtMillis")
    }
}

public struct SPFNEchoRequest: Equatable, Sendable
{
    public var message: String
    public var sequence: Int64

    public init(
        message: String,
        sequence: Int64
    )
    {
        self.message = message
        self.sequence = sequence
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["message"] = .string(message)
        members["sequence"] = .integer(sequence)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.message = try SPFNDecoding.string(members["message"], at: "\(path).message")
        self.sequence = try SPFNDecoding.integer(members["sequence"], at: "\(path).sequence")
    }
}

public struct SPFNEchoResponse: Equatable, Sendable
{
    public var message: String
    public var sequence: Int64
    public var serverTimeMillis: Int64

    public init(
        message: String,
        sequence: Int64,
        serverTimeMillis: Int64
    )
    {
        self.message = message
        self.sequence = sequence
        self.serverTimeMillis = serverTimeMillis
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["message"] = .string(message)
        members["sequence"] = .integer(sequence)
        members["serverTimeMillis"] = .integer(serverTimeMillis)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.message = try SPFNDecoding.string(members["message"], at: "\(path).message")
        self.sequence = try SPFNDecoding.integer(members["sequence"], at: "\(path).sequence")
        self.serverTimeMillis = try SPFNDecoding.integer(members["serverTimeMillis"], at: "\(path).serverTimeMillis")
    }
}

public struct SPFNListItemsRequest: Equatable, Sendable
{
    public var limit: Int64
    public var cursor: String?

    public init(
        limit: Int64,
        cursor: String? = nil
    )
    {
        self.limit = limit
        self.cursor = cursor
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["limit"] = .integer(limit)
        if let cursor
        {
            members["cursor"] = .string(cursor)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.limit = try SPFNDecoding.integer(members["limit"], at: "\(path).limit")
        self.cursor = try SPFNDecoding.optionalString(members["cursor"], at: "\(path).cursor")
    }
}

public struct SPFNItem: Equatable, Sendable
{
    public var id: String
    public var name: String
    public var updatedAtMillis: Int64

    public init(
        id: String,
        name: String,
        updatedAtMillis: Int64
    )
    {
        self.id = id
        self.name = name
        self.updatedAtMillis = updatedAtMillis
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["id"] = .string(id)
        members["name"] = .string(name)
        members["updatedAtMillis"] = .integer(updatedAtMillis)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.id = try SPFNDecoding.string(members["id"], at: "\(path).id")
        self.name = try SPFNDecoding.string(members["name"], at: "\(path).name")
        self.updatedAtMillis = try SPFNDecoding.integer(members["updatedAtMillis"], at: "\(path).updatedAtMillis")
    }
}

public struct SPFNListItemsResponse: Equatable, Sendable
{
    public var items: [SPFNItem]
    public var nextCursor: String?

    public init(
        items: [SPFNItem],
        nextCursor: String? = nil
    )
    {
        self.items = items
        self.nextCursor = nextCursor
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["items"] = .array(items.map { $0.canonicalValue })
        if let nextCursor
        {
            members["nextCursor"] = .string(nextCursor)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.items = try SPFNDecoding.array(members["items"], at: "\(path).items").map { try SPFNItem(canonical: $0, at: "\(path).items") }
        self.nextCursor = try SPFNDecoding.optionalString(members["nextCursor"], at: "\(path).nextCursor")
    }
}
