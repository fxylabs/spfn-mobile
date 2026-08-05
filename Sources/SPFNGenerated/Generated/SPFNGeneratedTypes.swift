// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    0a91612158aaf9917be8487cf70e1df9ab4c12ac6c1106973afa99122e458795
// contractVersion: 0.6.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

/// A value set the contract declares. Decoding is strict: an unknown value is
/// reported with the raw string preserved rather than mapped onto a member,
/// because the contract promises no set stays as it is — a value can be added,
/// and one can be withdrawn for a weakness found later.
public enum SPFNKeyAlgorithm: String, CaseIterable, Sendable
{
    case es256 = "ES256"
    case rs256 = "RS256"

    public var canonicalValue: SPFNCanonicalValue
    {
        .string(rawValue)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let raw = try SPFNDecoding.string(canonical, at: path)
        guard let value = SPFNKeyAlgorithm(rawValue: raw)
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "KeyAlgorithm")
        }
        self = value
    }
}

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

public struct SPFNRegisterRequest: Equatable, Sendable
{
    public var email: String?
    public var phone: String?
    public var verificationToken: String
    public var password: String
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: SPFNKeyAlgorithm

    public init(
        email: String? = nil,
        phone: String? = nil,
        verificationToken: String,
        password: String,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: SPFNKeyAlgorithm
    )
    {
        self.email = email
        self.phone = phone
        self.verificationToken = verificationToken
        self.password = password
        self.publicKey = publicKey
        self.keyId = keyId
        self.fingerprint = fingerprint
        self.algorithm = algorithm
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        if let email
        {
            members["email"] = .string(email)
        }
        if let phone
        {
            members["phone"] = .string(phone)
        }
        members["verificationToken"] = .string(verificationToken)
        members["password"] = .string(password)
        members["publicKey"] = .string(publicKey)
        members["keyId"] = .string(keyId)
        members["fingerprint"] = .string(fingerprint)
        members["algorithm"] = algorithm.canonicalValue
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.email = try SPFNDecoding.optionalString(members["email"], at: "\(path).email")
        self.phone = try SPFNDecoding.optionalString(members["phone"], at: "\(path).phone")
        self.verificationToken = try SPFNDecoding.string(members["verificationToken"], at: "\(path).verificationToken")
        self.password = try SPFNDecoding.string(members["password"], at: "\(path).password")
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNKeyAlgorithm(canonical: members["algorithm"] ?? .null, at: "\(path).algorithm")
    }
}

public struct SPFNRegisterResponse: Equatable, Sendable
{
    public var userId: String
    public var publicId: String
    public var email: String?
    public var phone: String?

    public init(
        userId: String,
        publicId: String,
        email: String? = nil,
        phone: String? = nil
    )
    {
        self.userId = userId
        self.publicId = publicId
        self.email = email
        self.phone = phone
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["userId"] = .string(userId)
        members["publicId"] = .string(publicId)
        if let email
        {
            members["email"] = .string(email)
        }
        if let phone
        {
            members["phone"] = .string(phone)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.userId = try SPFNDecoding.string(members["userId"], at: "\(path).userId")
        self.publicId = try SPFNDecoding.string(members["publicId"], at: "\(path).publicId")
        self.email = try SPFNDecoding.optionalString(members["email"], at: "\(path).email")
        self.phone = try SPFNDecoding.optionalString(members["phone"], at: "\(path).phone")
    }
}

public struct SPFNLoginRequest: Equatable, Sendable
{
    public var email: String?
    public var phone: String?
    public var password: String
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: SPFNKeyAlgorithm
    public var oldKeyId: String?

    public init(
        email: String? = nil,
        phone: String? = nil,
        password: String,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: SPFNKeyAlgorithm,
        oldKeyId: String? = nil
    )
    {
        self.email = email
        self.phone = phone
        self.password = password
        self.publicKey = publicKey
        self.keyId = keyId
        self.fingerprint = fingerprint
        self.algorithm = algorithm
        self.oldKeyId = oldKeyId
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        if let email
        {
            members["email"] = .string(email)
        }
        if let phone
        {
            members["phone"] = .string(phone)
        }
        members["password"] = .string(password)
        members["publicKey"] = .string(publicKey)
        members["keyId"] = .string(keyId)
        members["fingerprint"] = .string(fingerprint)
        members["algorithm"] = algorithm.canonicalValue
        if let oldKeyId
        {
            members["oldKeyId"] = .string(oldKeyId)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.email = try SPFNDecoding.optionalString(members["email"], at: "\(path).email")
        self.phone = try SPFNDecoding.optionalString(members["phone"], at: "\(path).phone")
        self.password = try SPFNDecoding.string(members["password"], at: "\(path).password")
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNKeyAlgorithm(canonical: members["algorithm"] ?? .null, at: "\(path).algorithm")
        self.oldKeyId = try SPFNDecoding.optionalString(members["oldKeyId"], at: "\(path).oldKeyId")
    }
}

public struct SPFNLoginResponse: Equatable, Sendable
{
    public var userId: String
    public var publicId: String
    public var email: String?
    public var phone: String?
    public var passwordChangeRequired: Bool

    public init(
        userId: String,
        publicId: String,
        email: String? = nil,
        phone: String? = nil,
        passwordChangeRequired: Bool
    )
    {
        self.userId = userId
        self.publicId = publicId
        self.email = email
        self.phone = phone
        self.passwordChangeRequired = passwordChangeRequired
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["userId"] = .string(userId)
        members["publicId"] = .string(publicId)
        if let email
        {
            members["email"] = .string(email)
        }
        if let phone
        {
            members["phone"] = .string(phone)
        }
        members["passwordChangeRequired"] = .bool(passwordChangeRequired)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.userId = try SPFNDecoding.string(members["userId"], at: "\(path).userId")
        self.publicId = try SPFNDecoding.string(members["publicId"], at: "\(path).publicId")
        self.email = try SPFNDecoding.optionalString(members["email"], at: "\(path).email")
        self.phone = try SPFNDecoding.optionalString(members["phone"], at: "\(path).phone")
        self.passwordChangeRequired = try SPFNDecoding.boolean(members["passwordChangeRequired"], at: "\(path).passwordChangeRequired")
    }
}

public struct SPFNOauthNativeRequest: Equatable, Sendable
{
    public var idToken: String
    public var nonce: String
    public var accessToken: String?
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: SPFNKeyAlgorithm

    public init(
        idToken: String,
        nonce: String,
        accessToken: String? = nil,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: SPFNKeyAlgorithm
    )
    {
        self.idToken = idToken
        self.nonce = nonce
        self.accessToken = accessToken
        self.publicKey = publicKey
        self.keyId = keyId
        self.fingerprint = fingerprint
        self.algorithm = algorithm
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["idToken"] = .string(idToken)
        members["nonce"] = .string(nonce)
        if let accessToken
        {
            members["accessToken"] = .string(accessToken)
        }
        members["publicKey"] = .string(publicKey)
        members["keyId"] = .string(keyId)
        members["fingerprint"] = .string(fingerprint)
        members["algorithm"] = algorithm.canonicalValue
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.idToken = try SPFNDecoding.string(members["idToken"], at: "\(path).idToken")
        self.nonce = try SPFNDecoding.string(members["nonce"], at: "\(path).nonce")
        self.accessToken = try SPFNDecoding.optionalString(members["accessToken"], at: "\(path).accessToken")
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNKeyAlgorithm(canonical: members["algorithm"] ?? .null, at: "\(path).algorithm")
    }
}

public struct SPFNOauthNativeResponse: Equatable, Sendable
{
    public var userId: String
    public var keyId: String
    public var isNewUser: Bool

    public init(
        userId: String,
        keyId: String,
        isNewUser: Bool
    )
    {
        self.userId = userId
        self.keyId = keyId
        self.isNewUser = isNewUser
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["userId"] = .string(userId)
        members["keyId"] = .string(keyId)
        members["isNewUser"] = .bool(isNewUser)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.userId = try SPFNDecoding.string(members["userId"], at: "\(path).userId")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.isNewUser = try SPFNDecoding.boolean(members["isNewUser"], at: "\(path).isNewUser")
    }
}

public struct SPFNRotateKeyRequest: Equatable, Sendable
{
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: SPFNKeyAlgorithm

    public init(
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: SPFNKeyAlgorithm
    )
    {
        self.publicKey = publicKey
        self.keyId = keyId
        self.fingerprint = fingerprint
        self.algorithm = algorithm
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["publicKey"] = .string(publicKey)
        members["keyId"] = .string(keyId)
        members["fingerprint"] = .string(fingerprint)
        members["algorithm"] = algorithm.canonicalValue
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNKeyAlgorithm(canonical: members["algorithm"] ?? .null, at: "\(path).algorithm")
    }
}

public struct SPFNRotateKeyResponse: Equatable, Sendable
{
    public var success: Bool
    public var keyId: String

    public init(
        success: Bool,
        keyId: String
    )
    {
        self.success = success
        self.keyId = keyId
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["success"] = .bool(success)
        members["keyId"] = .string(keyId)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.success = try SPFNDecoding.boolean(members["success"], at: "\(path).success")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
    }
}

public struct SPFNListKeysRequest: Equatable, Sendable
{
    public var includeRevoked: Bool?

    public init(
        includeRevoked: Bool? = nil
    )
    {
        self.includeRevoked = includeRevoked
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        if let includeRevoked
        {
            members["includeRevoked"] = .bool(includeRevoked)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.includeRevoked = try SPFNDecoding.boolean(members["includeRevoked"], at: "\(path).includeRevoked")
    }
}

public struct SPFNKeySummary: Equatable, Sendable
{
    public var keyId: String
    public var deviceName: String?
    public var platform: String?
    public var algorithm: SPFNKeyAlgorithm
    public var fingerprintPrefix: String
    public var createdAtMillis: Int64
    public var lastUsedAtMillis: Int64?
    public var expiresAtMillis: Int64?
    public var isExpired: Bool
    public var isActive: Bool
    public var revokedAtMillis: Int64?

    public init(
        keyId: String,
        deviceName: String? = nil,
        platform: String? = nil,
        algorithm: SPFNKeyAlgorithm,
        fingerprintPrefix: String,
        createdAtMillis: Int64,
        lastUsedAtMillis: Int64? = nil,
        expiresAtMillis: Int64? = nil,
        isExpired: Bool,
        isActive: Bool,
        revokedAtMillis: Int64? = nil
    )
    {
        self.keyId = keyId
        self.deviceName = deviceName
        self.platform = platform
        self.algorithm = algorithm
        self.fingerprintPrefix = fingerprintPrefix
        self.createdAtMillis = createdAtMillis
        self.lastUsedAtMillis = lastUsedAtMillis
        self.expiresAtMillis = expiresAtMillis
        self.isExpired = isExpired
        self.isActive = isActive
        self.revokedAtMillis = revokedAtMillis
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["keyId"] = .string(keyId)
        if let deviceName
        {
            members["deviceName"] = .string(deviceName)
        }
        if let platform
        {
            members["platform"] = .string(platform)
        }
        members["algorithm"] = algorithm.canonicalValue
        members["fingerprintPrefix"] = .string(fingerprintPrefix)
        members["createdAtMillis"] = .integer(createdAtMillis)
        if let lastUsedAtMillis
        {
            members["lastUsedAtMillis"] = .integer(lastUsedAtMillis)
        }
        if let expiresAtMillis
        {
            members["expiresAtMillis"] = .integer(expiresAtMillis)
        }
        members["isExpired"] = .bool(isExpired)
        members["isActive"] = .bool(isActive)
        if let revokedAtMillis
        {
            members["revokedAtMillis"] = .integer(revokedAtMillis)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.deviceName = try SPFNDecoding.optionalString(members["deviceName"], at: "\(path).deviceName")
        self.platform = try SPFNDecoding.optionalString(members["platform"], at: "\(path).platform")
        self.algorithm = try SPFNKeyAlgorithm(canonical: members["algorithm"] ?? .null, at: "\(path).algorithm")
        self.fingerprintPrefix = try SPFNDecoding.string(members["fingerprintPrefix"], at: "\(path).fingerprintPrefix")
        self.createdAtMillis = try SPFNDecoding.integer(members["createdAtMillis"], at: "\(path).createdAtMillis")
        self.lastUsedAtMillis = try SPFNDecoding.optionalInteger(members["lastUsedAtMillis"], at: "\(path).lastUsedAtMillis")
        self.expiresAtMillis = try SPFNDecoding.optionalInteger(members["expiresAtMillis"], at: "\(path).expiresAtMillis")
        self.isExpired = try SPFNDecoding.boolean(members["isExpired"], at: "\(path).isExpired")
        self.isActive = try SPFNDecoding.boolean(members["isActive"], at: "\(path).isActive")
        self.revokedAtMillis = try SPFNDecoding.optionalInteger(members["revokedAtMillis"], at: "\(path).revokedAtMillis")
    }
}

public struct SPFNListKeysResponse: Equatable, Sendable
{
    public var keys: [SPFNKeySummary]

    public init(
        keys: [SPFNKeySummary]
    )
    {
        self.keys = keys
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["keys"] = .array(keys.map { $0.canonicalValue })
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.keys = try SPFNDecoding.array(members["keys"], at: "\(path).keys").map { try SPFNKeySummary(canonical: $0, at: "\(path).keys") }
    }
}

public struct SPFNRevokeKeyRequest: Equatable, Sendable
{
    public var keyId: String

    public init(
        keyId: String
    )
    {
        self.keyId = keyId
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["keyId"] = .string(keyId)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
    }
}

public struct SPFNRevokeKeyResponse: Equatable, Sendable
{
    public var keyId: String
    public var selfRevoked: Bool

    public init(
        keyId: String,
        selfRevoked: Bool
    )
    {
        self.keyId = keyId
        self.selfRevoked = selfRevoked
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["keyId"] = .string(keyId)
        members["selfRevoked"] = .bool(selfRevoked)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.selfRevoked = try SPFNDecoding.boolean(members["selfRevoked"], at: "\(path).selfRevoked")
    }
}

public struct SPFNRevokeAllKeysRequest: Equatable, Sendable
{
    public var includeCurrent: Bool?

    public init(
        includeCurrent: Bool? = nil
    )
    {
        self.includeCurrent = includeCurrent
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        if let includeCurrent
        {
            members["includeCurrent"] = .bool(includeCurrent)
        }
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.includeCurrent = try SPFNDecoding.boolean(members["includeCurrent"], at: "\(path).includeCurrent")
    }
}

public struct SPFNRevokeAllKeysResponse: Equatable, Sendable
{
    public var revokedCount: Int64
    public var currentKeyRevoked: Bool

    public init(
        revokedCount: Int64,
        currentKeyRevoked: Bool
    )
    {
        self.revokedCount = revokedCount
        self.currentKeyRevoked = currentKeyRevoked
    }

    /// The canonical form of this value. An absent optional field is omitted,
    /// never written as null, so the digest of a value never depends on how a
    /// caller happened to spell "nothing".
    public var canonicalValue: SPFNCanonicalValue
    {
        var members: [String: SPFNCanonicalValue] = [:]
        members["revokedCount"] = .integer(revokedCount)
        members["currentKeyRevoked"] = .bool(currentKeyRevoked)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.revokedCount = try SPFNDecoding.integer(members["revokedCount"], at: "\(path).revokedCount")
        self.currentKeyRevoked = try SPFNDecoding.boolean(members["currentKeyRevoked"], at: "\(path).currentKeyRevoked")
    }
}
