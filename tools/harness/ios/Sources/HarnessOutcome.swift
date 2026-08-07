// SPFN Mobile — one short stable name per failure.
//
// A Maestro flow asserts on text, so the text has to be a name rather than a sentence.
// Every name here comes from the SDK's own vocabulary: a lifecycle refusal is its case
// name, a server refusal is the contract's error code, and a transport failure is its
// class. Nothing is invented and nothing is translated, so a flow that asserts
// `err:SESSION_REVOKED` is asserting on the contract.
//
// An error this file does not recognise becomes `unclassified`, never a guess. A name
// that quietly covered an unknown error would let a flow pass on the wrong refusal.

import Foundation
import SPFNAuth
import SPFNClient

enum HarnessOutcome
{
    static func name(for error: Error) -> String
    {
        switch error
        {
        case let error as SPFNKeyLifecycleError:
            return name(forLifecycle: error)
        case let error as SPFNClientError:
            return name(forClient: error)
        case let error as SPFNTransportError:
            return name(forTransport: error)
        case let error as SPFNKeyStoreError:
            // The OSStatus, because the reason a keychain refused is the whole message.
            // Added after a run reported `unclassified` for every case and the cause —
            // an unsigned build has no keychain entitlement, so every store call fails
            // with -34018 — was invisible until this line existed.
            return "keystore:\(error.status)"
        case let error as HarnessError:
            return name(forHarness: error)
        default:
            return "unclassified"
        }
    }

    private static func name(forLifecycle error: SPFNKeyLifecycleError) -> String
    {
        switch error
        {
        case .alreadyEnrolled:
            return "alreadyEnrolled"
        case .notEnrolled:
            return "notEnrolled"
        case .rotationUnresolved:
            return "rotationUnresolved"
        case .enrollmentInFlight:
            return "enrollmentInFlight"
        case .idTokenMissing:
            return "idTokenMissing"
        case .malformedProviderID:
            return "malformedProviderID"
        case .serverNamedAnotherKey:
            return "serverNamedAnotherKey"
        case .keyUnloadable:
            return "keyUnloadable"
        }
    }

    /// A server refusal reports the contract's own code, which is the thing worth
    /// asserting on: `SESSION_REVOKED` after a revocation is the contract behaving.
    private static func name(forClient error: SPFNClientError) -> String
    {
        switch error
        {
        case .transport(let error):
            return name(forTransport: error)
        case .auth(let failure):
            return failure.code.rawValue
        case .server(let failure):
            return failure.code.rawValue
        case .decoding(let failure):
            return "decoding:\(failure.rawValue)"
        // The reason alone. The server's version is in the error and is deliberately not
        // put on a readout a flow asserts on: a readout that carried it would make every
        // assertion depend on which server answered.
        case .contract(let mismatch):
            return "contract:\(mismatch.reason.rawValue)"
        case .unsupportedOperation:
            return "unsupportedOperation"
        case .undeclaredAuthClass:
            return "undeclaredAuthClass"
        }
    }

    private static func name(forTransport error: SPFNTransportError) -> String
    {
        switch error
        {
        case .connectivity:
            return "connectivity"
        case .timedOut:
            return "timedOut"
        case .cancelled:
            return "cancelled"
        case .invalidResponse:
            return "invalidResponse"
        }
    }

    private static func name(forHarness error: HarnessError) -> String
    {
        switch error
        {
        case .noCannedToken:
            return "harness:noCannedToken"
        case .noActiveKey:
            return "harness:noActiveKey"
        }
    }
}
