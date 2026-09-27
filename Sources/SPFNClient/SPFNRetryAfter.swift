// SPFN Mobile — reading a 429's Retry-After (design §10 Q-D).
//
// The execute path has no Retry-After rule of its own: it retries nothing but an auth
// refusal. So this is the one rule, and both halves of the event stream use it — the
// token call reads the header off `SPFNServerFailure.retryAfter`, the stream open reads it
// off the response. RFC 9110 §10.2.3 admits two spellings, delay-seconds and an HTTP-date;
// anything else is ignored rather than guessed at, and the backoff stands alone.
//
// android/spfn-client/.../SpfnRetryAfter.kt is the same rule in Kotlin.

import Foundation

public enum SPFNRetryAfter
{
    /// The most a server's Retry-After can hold the stream back: five minutes.
    public static let capMillis: Int64 = 300_000

    /// The wait the header asks for, in milliseconds, capped at `capMillis`; a date in the
    /// past is 0. Nil when the header is absent or malformed.
    public static func millis(_ value: String?, nowMillis: Int64) -> Int64?
    {
        guard let text = value?.trimmingCharacters(in: .whitespaces)
        else
        {
            return nil
        }
        if !text.isEmpty, text.utf8.allSatisfy({ $0 >= UInt8(ascii: "0") && $0 <= UInt8(ascii: "9") })
        {
            guard let seconds = Int64(text), seconds <= capMillis / 1_000
            else
            {
                return capMillis
            }
            return seconds * 1_000
        }
        guard let date = dateMillis(text)
        else
        {
            return nil
        }
        return min(max(date - nowMillis, 0), capMillis)
    }

    /// The header's value in `headers`, matched case-insensitively.
    public static func header(in headers: [(String, String)]) -> String?
    {
        headers.first { $0.0.lowercased() == "retry-after" }?.1
    }

    /// An IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`). A new formatter per call: a 429
    /// is rare, and a shared formatter would need its own isolation.
    private static func dateMillis(_ text: String) -> Int64?
    {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let date = formatter.date(from: text)
        else
        {
            return nil
        }
        return Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }
}
