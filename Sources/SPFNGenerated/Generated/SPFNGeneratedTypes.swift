// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    a41a3c06c9d995d4865613daa698c207ba66b53ee5c25a71015c730e7253538d
// contractVersion: 0.3.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
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

public struct SPFNRegisterRequest: Equatable, Sendable
{
    public var email: String?
    public var phone: String?
    public var verificationToken: String
    public var password: String
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: String

    public init(
        email: String? = nil,
        phone: String? = nil,
        verificationToken: String,
        password: String,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: String
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
        members["algorithm"] = .string(algorithm)
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
        self.algorithm = try SPFNDecoding.string(members["algorithm"], at: "\(path).algorithm")
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
    public var algorithm: String
    public var oldKeyId: String?

    public init(
        email: String? = nil,
        phone: String? = nil,
        password: String,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: String,
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
        members["algorithm"] = .string(algorithm)
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
        self.algorithm = try SPFNDecoding.string(members["algorithm"], at: "\(path).algorithm")
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
    public var publicKey: String
    public var keyId: String
    public var fingerprint: String
    public var algorithm: String

    public init(
        idToken: String,
        nonce: String,
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: String
    )
    {
        self.idToken = idToken
        self.nonce = nonce
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
        members["publicKey"] = .string(publicKey)
        members["keyId"] = .string(keyId)
        members["fingerprint"] = .string(fingerprint)
        members["algorithm"] = .string(algorithm)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.idToken = try SPFNDecoding.string(members["idToken"], at: "\(path).idToken")
        self.nonce = try SPFNDecoding.string(members["nonce"], at: "\(path).nonce")
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNDecoding.string(members["algorithm"], at: "\(path).algorithm")
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
    public var algorithm: String

    public init(
        publicKey: String,
        keyId: String,
        fingerprint: String,
        algorithm: String
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
        members["algorithm"] = .string(algorithm)
        return .object(members)
    }

    public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
    {
        let members = try SPFNDecoding.object(canonical, at: path)
        self.publicKey = try SPFNDecoding.string(members["publicKey"], at: "\(path).publicKey")
        self.keyId = try SPFNDecoding.string(members["keyId"], at: "\(path).keyId")
        self.fingerprint = try SPFNDecoding.string(members["fingerprint"], at: "\(path).fingerprint")
        self.algorithm = try SPFNDecoding.string(members["algorithm"], at: "\(path).algorithm")
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
