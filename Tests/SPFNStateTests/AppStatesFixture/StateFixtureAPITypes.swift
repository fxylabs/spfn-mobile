// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9699aecc37719004922e7991e1be7cf224a467f44e82cce15b37528b37b0d2bb
// documentVersion: 1
// operations:      getAiAccountsState, getConsentState, getStates, putConsentState
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

import SPFNCore

extension StateFixtureAPI
{
    public struct GetAiAccountsStateResponse: Equatable, Sendable
    {
        public var at: String
        public var value: GetAiAccountsStateResponseValue

        public init(
            at: String,
            value: GetAiAccountsStateResponseValue
        )
        {
            self.at = at
            self.value = value
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.at = try SPFNDecoding.string(members["at"], at: "\(path).at")
            self.value = try GetAiAccountsStateResponseValue(canonical: members["value"] ?? .null, at: "\(path).value")
        }
    }

    public struct GetAiAccountsStateResponseValue: Equatable, Sendable
    {
        public var connected: Bool
        public var providers: [String]

        public init(
            connected: Bool,
            providers: [String]
        )
        {
            self.connected = connected
            self.providers = providers
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.connected = try SPFNDecoding.boolean(members["connected"], at: "\(path).connected")
            self.providers = try SPFNDecoding.array(members["providers"], at: "\(path).providers").map { try SPFNDecoding.string($0, at: "\(path).providers") }
        }
    }

    public struct GetConsentStateResponse: Equatable, Sendable
    {
        public var at: String
        public var data: GetConsentStateResponseData
        public var value: GetConsentStateResponseValue

        public init(
            at: String,
            data: GetConsentStateResponseData,
            value: GetConsentStateResponseValue
        )
        {
            self.at = at
            self.data = data
            self.value = value
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.at = try SPFNDecoding.string(members["at"], at: "\(path).at")
            self.data = try GetConsentStateResponseData(canonical: members["data"] ?? .null, at: "\(path).data")
            self.value = try GetConsentStateResponseValue(wireValue: SPFNDecoding.string(members["value"], at: "\(path).value"))
        }
    }

    public struct GetConsentStateResponseData: Equatable, Sendable
    {
        public var marketing: Bool
        public var version: Int64

        public init(
            marketing: Bool,
            version: Int64
        )
        {
            self.marketing = marketing
            self.version = version
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.marketing = try SPFNDecoding.boolean(members["marketing"], at: "\(path).marketing")
            self.version = try SPFNDecoding.integer(members["version"], at: "\(path).version")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum GetConsentStateResponseValue: Hashable, Sendable
    {
        case pending
        case agreed
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "pending":
                self = .pending
            case "agreed":
                self = .agreed
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .pending:
                return "pending"
            case .agreed:
                return "agreed"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct GetStatesResponse: Equatable, Sendable
    {
        public var at: String
        public var manifest: GetStatesResponseManifest
        public var states: GetStatesResponseStates

        public init(
            at: String,
            manifest: GetStatesResponseManifest,
            states: GetStatesResponseStates
        )
        {
            self.at = at
            self.manifest = manifest
            self.states = states
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.at = try SPFNDecoding.string(members["at"], at: "\(path).at")
            self.manifest = try GetStatesResponseManifest(canonical: members["manifest"] ?? .null, at: "\(path).manifest")
            self.states = try GetStatesResponseStates(canonical: members["states"] ?? .null, at: "\(path).states")
        }
    }

    public struct GetStatesResponseManifest: Equatable, Sendable
    {
        public var aiAccounts: GetStatesResponseManifestAiAccounts
        public var consent: GetStatesResponseManifestConsent

        public init(
            aiAccounts: GetStatesResponseManifestAiAccounts,
            consent: GetStatesResponseManifestConsent
        )
        {
            self.aiAccounts = aiAccounts
            self.consent = consent
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.aiAccounts = try GetStatesResponseManifestAiAccounts(canonical: members["aiAccounts"] ?? .null, at: "\(path).aiAccounts")
            self.consent = try GetStatesResponseManifestConsent(canonical: members["consent"] ?? .null, at: "\(path).consent")
        }
    }

    public struct GetStatesResponseManifestAiAccounts: Equatable, Sendable
    {
        public var key: [String]
        public var write: Bool

        public init(
            key: [String],
            write: Bool
        )
        {
            self.key = key
            self.write = write
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.key = try SPFNDecoding.array(members["key"], at: "\(path).key").map { try SPFNDecoding.string($0, at: "\(path).key") }
            self.write = try SPFNDecoding.boolean(members["write"], at: "\(path).write")
        }
    }

    public struct GetStatesResponseManifestConsent: Equatable, Sendable
    {
        public var key: [String]
        public var write: Bool

        public init(
            key: [String],
            write: Bool
        )
        {
            self.key = key
            self.write = write
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.key = try SPFNDecoding.array(members["key"], at: "\(path).key").map { try SPFNDecoding.string($0, at: "\(path).key") }
            self.write = try SPFNDecoding.boolean(members["write"], at: "\(path).write")
        }
    }

    public struct GetStatesResponseStates: Equatable, Sendable
    {
        public var aiAccounts: GetStatesResponseStatesAiAccounts?
        public var consent: GetStatesResponseStatesConsent?

        public init(
            aiAccounts: GetStatesResponseStatesAiAccounts? = nil,
            consent: GetStatesResponseStatesConsent? = nil
        )
        {
            self.aiAccounts = aiAccounts
            self.consent = consent
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.aiAccounts = try StateFixtureAPI.nonNull(members["aiAccounts"]).map { try GetStatesResponseStatesAiAccounts(canonical: $0, at: "\(path).aiAccounts") }
            self.consent = try StateFixtureAPI.nonNull(members["consent"]).map { try GetStatesResponseStatesConsent(canonical: $0, at: "\(path).consent") }
        }
    }

    public struct GetStatesResponseStatesAiAccounts: Equatable, Sendable
    {
        public var value: GetStatesResponseStatesAiAccountsValue

        public init(
            value: GetStatesResponseStatesAiAccountsValue
        )
        {
            self.value = value
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.value = try GetStatesResponseStatesAiAccountsValue(canonical: members["value"] ?? .null, at: "\(path).value")
        }
    }

    public struct GetStatesResponseStatesAiAccountsValue: Equatable, Sendable
    {
        public var connected: Bool
        public var providers: [String]

        public init(
            connected: Bool,
            providers: [String]
        )
        {
            self.connected = connected
            self.providers = providers
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.connected = try SPFNDecoding.boolean(members["connected"], at: "\(path).connected")
            self.providers = try SPFNDecoding.array(members["providers"], at: "\(path).providers").map { try SPFNDecoding.string($0, at: "\(path).providers") }
        }
    }

    public struct GetStatesResponseStatesConsent: Equatable, Sendable
    {
        public var data: GetStatesResponseStatesConsentData
        public var value: GetStatesResponseStatesConsentValue

        public init(
            data: GetStatesResponseStatesConsentData,
            value: GetStatesResponseStatesConsentValue
        )
        {
            self.data = data
            self.value = value
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.data = try GetStatesResponseStatesConsentData(canonical: members["data"] ?? .null, at: "\(path).data")
            self.value = try GetStatesResponseStatesConsentValue(wireValue: SPFNDecoding.string(members["value"], at: "\(path).value"))
        }
    }

    public struct GetStatesResponseStatesConsentData: Equatable, Sendable
    {
        public var marketing: Bool
        public var version: Int64

        public init(
            marketing: Bool,
            version: Int64
        )
        {
            self.marketing = marketing
            self.version = version
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.marketing = try SPFNDecoding.boolean(members["marketing"], at: "\(path).marketing")
            self.version = try SPFNDecoding.integer(members["version"], at: "\(path).version")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum GetStatesResponseStatesConsentValue: Hashable, Sendable
    {
        case pending
        case agreed
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "pending":
                self = .pending
            case "agreed":
                self = .agreed
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .pending:
                return "pending"
            case .agreed:
                return "agreed"
            case .unknown(let value):
                return value
            }
        }
    }

    public struct PutConsentStateBody: Equatable, Sendable
    {
        public var agreed: Bool
        public var marketing: Bool

        public init(
            agreed: Bool,
            marketing: Bool
        )
        {
            self.agreed = agreed
            self.marketing = marketing
        }

        public func canonicalValue() -> SPFNCanonicalValue
        {
            var members: [String: SPFNCanonicalValue] = [:]
            members["agreed"] = .bool(self.agreed)
            members["marketing"] = .bool(self.marketing)
            return .object(members)
        }
    }

    public struct PutConsentStateResponse: Equatable, Sendable
    {
        public var at: String
        public var data: PutConsentStateResponseData
        public var value: PutConsentStateResponseValue

        public init(
            at: String,
            data: PutConsentStateResponseData,
            value: PutConsentStateResponseValue
        )
        {
            self.at = at
            self.data = data
            self.value = value
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.at = try SPFNDecoding.string(members["at"], at: "\(path).at")
            self.data = try PutConsentStateResponseData(canonical: members["data"] ?? .null, at: "\(path).data")
            self.value = try PutConsentStateResponseValue(wireValue: SPFNDecoding.string(members["value"], at: "\(path).value"))
        }
    }

    public struct PutConsentStateResponseData: Equatable, Sendable
    {
        public var marketing: Bool
        public var version: Int64

        public init(
            marketing: Bool,
            version: Int64
        )
        {
            self.marketing = marketing
            self.version = version
        }

        public init(canonical: SPFNCanonicalValue, at path: String = "$") throws
        {
            let members = try SPFNDecoding.object(canonical, at: path)
            self.marketing = try SPFNDecoding.boolean(members["marketing"], at: "\(path).marketing")
            self.version = try SPFNDecoding.integer(members["version"], at: "\(path).version")
        }
    }

    /// An open set: a value this build does not know decodes as `unknown`, so a server
    /// that adds one does not fail the whole response.
    public enum PutConsentStateResponseValue: Hashable, Sendable
    {
        case pending
        case agreed
        case unknown(String)

        public init(wireValue: String)
        {
            switch wireValue
            {
            case "pending":
                self = .pending
            case "agreed":
                self = .agreed
            default:
                self = .unknown(wireValue)
            }
        }

        public var wireValue: String
        {
            switch self
            {
            case .pending:
                return "pending"
            case .agreed:
                return "agreed"
            case .unknown(let value):
                return value
            }
        }
    }
}
