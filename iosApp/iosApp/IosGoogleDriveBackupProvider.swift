import AuthenticationServices
import CryptoKit
import Foundation
import Security
import SharedUI
import UIKit

/// Native Google Drive REST v3 + OAuth 2.0 PKCE (`ASWebAuthenticationSession`)
/// provider for iOS with Keychain token persistence and zero third-party SDKs.
final class IosGoogleDriveBackupProvider: NSObject, GoogleDriveBackupProvider, ASWebAuthenticationPresentationContextProviding {
    static let shared = IosGoogleDriveBackupProvider()

    private let driveFilesURL = "https://www.googleapis.com/drive/v3/files"
    private let driveUploadURL = "https://www.googleapis.com/upload/drive/v3/files"
    private let driveAboutURL = "https://www.googleapis.com/drive/v3/about"
    private let oauthAuthURL = "https://accounts.google.com/o/oauth2/v2/auth"
    private let oauthTokenURL = "https://oauth2.googleapis.com/token"
    private let oauthRevokeURL = "https://oauth2.googleapis.com/revoke"
    private let folderMimeType = "application/vnd.google-apps.folder"
    private let jsonMimeType = "application/json"

    private let keychainAccount = "google_drive_oauth_tokens"
    private var keychainService: String {
        Bundle.main.bundleIdentifier ?? "dev.balaji.moonrose.trainfree"
    }

    private var authSession: ASWebAuthenticationSession?

    private override init() {
        super.init()
    }

    // MARK: - Config

    private var clientId: String? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "GoogleDriveClientId") as? String else {
            return nil
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("$(") else { return nil }
        return trimmed
    }

    private var reversedClientId: String? {
        guard let clientId else { return nil }
        return clientId.split(separator: ".").reversed().joined(separator: ".")
    }

    // MARK: - GoogleDriveBackupProvider

    func availability() async throws -> DriveBackupAvailability {
        clientId != nil ? .available : .notConfigured
    }

    func downloadBackup(knownFileId: String?) async throws -> DriveDownloadResult {
        guard clientId != nil else { return DriveDownloadFailure(kind: .notConfigured) }
        do {
            return try await withFreshToken { token in
                let email = try? await self.fetchAccountEmail(accessToken: token)
                guard let fileId = try await self.findBackupFile(accessToken: token, knownFileId: knownFileId) else {
                    return DriveDownloadNotFound(accountEmail: email)
                }
                do {
                    let json = try await self.downloadFileMedia(accessToken: token, fileId: fileId)
                    return DriveDownloadFound(fileId: fileId, json: json, accountEmail: email)
                } catch is DriveNotFoundError {
                    return DriveDownloadNotFound(accountEmail: email)
                }
            }
        } catch {
            return DriveDownloadFailure(kind: mapError(error))
        }
    }

    func uploadBackup(json: String, knownFileId: String?) async throws -> DriveUploadResult {
        guard clientId != nil else { return DriveUploadFailure(kind: .notConfigured) }
        let bgTaskId = await beginBackgroundTask()
        defer {
            Task { @MainActor in
                if bgTaskId != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTaskId)
                }
            }
        }
        do {
            return try await withFreshToken { token in
                let email = try? await self.fetchAccountEmail(accessToken: token)
                let fileId = try await self.performUpload(accessToken: token, json: json, knownFileId: knownFileId)
                let nowMillis = Int64(Date().timeIntervalSince1970 * 1000.0)
                return DriveUploadSuccess(fileId: fileId, accountEmail: email, syncedAtMillis: nowMillis)
            }
        } catch {
            return DriveUploadFailure(kind: mapError(error))
        }
    }

    func disconnect(accountEmail: String?) async throws {
        if let tokens = loadTokens() {
            let tokenToRevoke = tokens.refreshToken ?? tokens.accessToken
            await revokeToken(tokenToRevoke)
        }
        deleteTokens()
    }

    func scheduleBackgroundRetry() {
        // iOS relies on beginBackgroundTask for immediate post-workout completion
        // and retries pending syncs on scenePhase == .active in iOSApp.swift.
    }

    func cancelBackgroundRetry() {}

    // MARK: - Interactive OAuth 2.0 PKCE

    @MainActor
    func signInInteractively() async -> DriveErrorKind? {
        guard let clientId, let reversedClientId else {
            return .notConfigured
        }
        let redirectUri = "\(reversedClientId):/oauth2redirect/google"
        let codeVerifier = Self.generateCodeVerifier()
        let codeChallenge = Self.codeChallengeS256(for: codeVerifier)
        let state = Self.generateCodeVerifier()

        var components = URLComponents(string: oauthAuthURL)!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: redirectUri),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: GoogleDriveBackupProviderKt.DRIVE_BACKUP_SCOPE),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent select_account"),
        ]
        guard let authURL = components.url else { return .unknown }

        do {
            let callbackURL: URL = try await withCheckedThrowingContinuation { continuation in
                let session = ASWebAuthenticationSession(
                    url: authURL,
                    callbackURLScheme: reversedClientId
                ) { url, error in
                    self.authSession = nil
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let url {
                        continuation.resume(returning: url)
                    } else {
                        continuation.resume(throwing: DriveAuthError.cancelled)
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                self.authSession = session
                if !session.start() {
                    self.authSession = nil
                    continuation.resume(throwing: DriveAuthError.unknown)
                }
            }

            let items = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems ?? []
            if let errorParam = items.first(where: { $0.name == "error" })?.value {
                return errorParam == "access_denied" ? .cancelled : .authRequired
            }
            guard items.first(where: { $0.name == "state" })?.value == state,
                  let code = items.first(where: { $0.name == "code" })?.value,
                  !code.isEmpty else {
                return .authRequired
            }

            try await exchangeAuthorizationCode(
                code: code,
                clientId: clientId,
                redirectUri: redirectUri,
                codeVerifier: codeVerifier
            )
            return nil
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            return .cancelled
        } catch {
            return mapError(error)
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first(where: { $0.isKeyWindow }) ?? windows.first ?? ASPresentationAnchor()
    }

    // MARK: - Token Lifecycle

    private struct StoredTokens: Codable {
        var accessToken: String
        var refreshToken: String?
        var expiresAtEpochSeconds: TimeInterval
    }

    private func exchangeAuthorizationCode(
        code: String,
        clientId: String,
        redirectUri: String,
        codeVerifier: String
    ) async throws {
        let params = [
            "code": code,
            "client_id": clientId,
            "redirect_uri": redirectUri,
            "grant_type": "authorization_code",
            "code_verifier": codeVerifier,
        ]
        let response = try await postForm(urlString: oauthTokenURL, parameters: params)
        guard let accessToken = response["access_token"] as? String, !accessToken.isEmpty else {
            throw DriveAuthError.authRequired
        }
        let refreshToken = (response["refresh_token"] as? String) ?? loadTokens()?.refreshToken
        let expiresIn = (response["expires_in"] as? Double) ?? 3600.0
        saveTokens(
            StoredTokens(
                accessToken: accessToken,
                refreshToken: refreshToken,
                expiresAtEpochSeconds: Date().timeIntervalSince1970 + expiresIn
            )
        )
    }

    private func withFreshToken<T>(_ block: (String) async throws -> T) async throws -> T {
        let token = try await obtainAccessToken(forceRefresh: false)
        do {
            return try await block(token)
        } catch is DriveUnauthorizedError {
            let refreshed = try await obtainAccessToken(forceRefresh: true)
            return try await block(refreshed)
        }
    }

    private func obtainAccessToken(forceRefresh: Bool) async throws -> String {
        guard let clientId else { throw DriveAuthError.notConfigured }
        guard let tokens = loadTokens() else { throw DriveAuthError.authRequired }
        if !forceRefresh && Date().timeIntervalSince1970 < (tokens.expiresAtEpochSeconds - 60.0) {
            return tokens.accessToken
        }
        guard let refreshToken = tokens.refreshToken, !refreshToken.isEmpty else {
            deleteTokens()
            throw DriveAuthError.authRequired
        }
        let params = [
            "client_id": clientId,
            "refresh_token": refreshToken,
            "grant_type": "refresh_token",
        ]
        do {
            let response = try await postForm(urlString: oauthTokenURL, parameters: params)
            guard let newAccess = response["access_token"] as? String, !newAccess.isEmpty else {
                throw DriveAuthError.authRequired
            }
            let expiresIn = (response["expires_in"] as? Double) ?? 3600.0
            let updated = StoredTokens(
                accessToken: newAccess,
                refreshToken: (response["refresh_token"] as? String) ?? refreshToken,
                expiresAtEpochSeconds: Date().timeIntervalSince1970 + expiresIn
            )
            saveTokens(updated)
            return newAccess
        } catch let oauthError as DriveInvalidGrantError {
            deleteTokens()
            throw oauthError
        }
    }

    private func postForm(urlString: String, parameters: [String: String]) async throws -> [String: Any] {
        guard let url = URL(string: urlString) else { throw DriveAuthError.unknown }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=?")
        let body = parameters.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(k)=\(v)"
        }.joined(separator: "&")
        request.httpBody = Data(body.utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw DriveAuthError.unknown }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if (200...299).contains(http.statusCode) {
            return json
        }
        if (json["error"] as? String) == "invalid_grant" {
            throw DriveInvalidGrantError()
        }
        if http.statusCode == 401 {
            throw DriveUnauthorizedError()
        }
        throw DriveAuthError.authRequired
    }

    private func revokeToken(_ token: String) async {
        guard var components = URLComponents(string: oauthRevokeURL) else { return }
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        guard let url = components.url else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        _ = try? await URLSession.shared.data(for: request)
    }

    // MARK: - Drive REST v3

    private func fetchAccountEmail(accessToken: String) async throws -> String? {
        var components = URLComponents(string: driveAboutURL)!
        components.queryItems = [URLQueryItem(name: "fields", value: "user(emailAddress)")]
        let data = try await requestData(method: "GET", url: components.url!, accessToken: accessToken, maxBytes: 1_048_576)
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let user = obj?["user"] as? [String: Any]
        let email = user?["emailAddress"] as? String
        return email?.isEmpty == false ? email : nil
    }

    private func findBackupFile(accessToken: String, knownFileId: String?) async throws -> String? {
        if let knownFileId, !knownFileId.isEmpty {
            if try await verifyFileExists(accessToken: accessToken, fileId: knownFileId) {
                return knownFileId
            }
        }
        let propKey = GoogleDriveBackupProviderKt.DRIVE_APP_PROPERTY_KEY
        let propVal = GoogleDriveBackupProviderKt.DRIVE_APP_PROPERTY_VALUE
        let byProperty = "appProperties has { key='\(propKey)' and value='\(propVal)' } and trashed = false"
        if let found = try await searchFiles(accessToken: accessToken, query: byProperty).first {
            return found
        }
        guard let folderId = try await findFolder(accessToken: accessToken) else { return nil }
        let filename = escapeQuery(GoogleDriveBackupProviderKt.DRIVE_BACKUP_FILENAME)
        let byName = "'\(escapeQuery(folderId))' in parents and name = '\(filename)' and trashed = false"
        return try await searchFiles(accessToken: accessToken, query: byName).first
    }

    private func verifyFileExists(accessToken: String, fileId: String) async throws -> Bool {
        let encodedId = fileId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? fileId
        var components = URLComponents(string: "\(driveFilesURL)/\(encodedId)")!
        components.queryItems = [URLQueryItem(name: "fields", value: "id,trashed")]
        do {
            let data = try await requestData(method: "GET", url: components.url!, accessToken: accessToken, maxBytes: 1_048_576)
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let trashed = (obj?["trashed"] as? Bool) ?? false
            return !trashed && (obj?["id"] as? String)?.isEmpty == false
        } catch is DriveNotFoundError {
            return false
        }
    }

    private func findFolder(accessToken: String) async throws -> String? {
        let folderName = escapeQuery(GoogleDriveBackupProviderKt.DRIVE_BACKUP_FOLDER_NAME)
        let query = "name = '\(folderName)' and mimeType = '\(folderMimeType)' and trashed = false"
        return try await searchFiles(accessToken: accessToken, query: query).first
    }

    private func createFolder(accessToken: String) async throws -> String {
        var components = URLComponents(string: driveFilesURL)!
        components.queryItems = [URLQueryItem(name: "fields", value: "id")]
        let payload: [String: Any] = [
            "name": GoogleDriveBackupProviderKt.DRIVE_BACKUP_FOLDER_NAME,
            "mimeType": folderMimeType,
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await requestData(
            method: "POST",
            url: components.url!,
            accessToken: accessToken,
            contentType: "application/json; charset=UTF-8",
            body: body,
            maxBytes: 1_048_576
        )
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let id = obj?["id"] as? String, !id.isEmpty else { throw DriveAuthError.unknown }
        return id
    }

    private func searchFiles(accessToken: String, query: String) async throws -> [String] {
        var components = URLComponents(string: driveFilesURL)!
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "spaces", value: "drive"),
            URLQueryItem(name: "fields", value: "files(id,trashed)"),
            URLQueryItem(name: "orderBy", value: "modifiedTime desc"),
            URLQueryItem(name: "pageSize", value: "10"),
        ]
        let data = try await requestData(method: "GET", url: components.url!, accessToken: accessToken, maxBytes: 2_097_152)
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let files = obj?["files"] as? [[String: Any]] ?? []
        return files.compactMap { item in
            let trashed = (item["trashed"] as? Bool) ?? false
            guard !trashed, let id = item["id"] as? String, !id.isEmpty else { return nil }
            return id
        }
    }

    private func performUpload(accessToken: String, json: String, knownFileId: String?) async throws -> String {
        if let existingId = try await findBackupFile(accessToken: accessToken, knownFileId: knownFileId) {
            return try await patchMedia(accessToken: accessToken, fileId: existingId, json: json)
        }
        let folderId: String
        if let existingFolder = try await findFolder(accessToken: accessToken) {
            folderId = existingFolder
        } else {
            folderId = try await createFolder(accessToken: accessToken)
        }
        return try await createMultipartFile(accessToken: accessToken, folderId: folderId, json: json)
    }

    private func patchMedia(accessToken: String, fileId: String, json: String) async throws -> String {
        let encodedId = fileId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? fileId
        var components = URLComponents(string: "\(driveUploadURL)/\(encodedId)")!
        components.queryItems = [
            URLQueryItem(name: "uploadType", value: "media"),
            URLQueryItem(name: "fields", value: "id"),
        ]
        let data = try await requestData(
            method: "PATCH",
            url: components.url!,
            accessToken: accessToken,
            contentType: "application/json; charset=UTF-8",
            body: Data(json.utf8),
            maxBytes: 1_048_576
        )
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let id = obj?["id"] as? String, !id.isEmpty else { throw DriveAuthError.unknown }
        return id
    }

    private func createMultipartFile(accessToken: String, folderId: String, json: String) async throws -> String {
        var components = URLComponents(string: driveUploadURL)!
        components.queryItems = [
            URLQueryItem(name: "uploadType", value: "multipart"),
            URLQueryItem(name: "fields", value: "id"),
        ]
        let boundary = "trainfree_\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let metadata: [String: Any] = [
            "name": GoogleDriveBackupProviderKt.DRIVE_BACKUP_FILENAME,
            "parents": [folderId],
            "mimeType": jsonMimeType,
            "appProperties": [
                GoogleDriveBackupProviderKt.DRIVE_APP_PROPERTY_KEY: GoogleDriveBackupProviderKt.DRIVE_APP_PROPERTY_VALUE,
            ],
        ]
        let metadataData = try JSONSerialization.data(withJSONObject: metadata)
        let metadataString = String(data: metadataData, encoding: .utf8) ?? "{}"
        let bodyString = """
        --\(boundary)\r
        Content-Type: application/json; charset=UTF-8\r
        \r
        \(metadataString)\r
        --\(boundary)\r
        Content-Type: application/json; charset=UTF-8\r
        \r
        \(json)\r
        --\(boundary)--\r
        """
        let data = try await requestData(
            method: "POST",
            url: components.url!,
            accessToken: accessToken,
            contentType: "multipart/related; boundary=\(boundary)",
            body: Data(bodyString.utf8),
            maxBytes: 1_048_576
        )
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let id = obj?["id"] as? String, !id.isEmpty else { throw DriveAuthError.unknown }
        return id
    }

    private func downloadFileMedia(accessToken: String, fileId: String) async throws -> String {
        let encodedId = fileId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? fileId
        var components = URLComponents(string: "\(driveFilesURL)/\(encodedId)")!
        components.queryItems = [URLQueryItem(name: "alt", value: "media")]
        let maxBytes = Int(BackupRepositoryKt.MAX_BACKUP_FILE_BYTES)
        let data = try await requestData(method: "GET", url: components.url!, accessToken: accessToken, maxBytes: maxBytes)
        guard let text = String(data: data, encoding: .utf8) else { throw DriveAuthError.unknown }
        return text
    }

    private func requestData(
        method: String,
        url: URL,
        accessToken: String,
        contentType: String? = nil,
        body: Data? = nil,
        maxBytes: Int
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let contentType {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw DriveAuthError.unknown }
        if (200...299).contains(http.statusCode) {
            if data.count > maxBytes { throw DriveAuthError.unknown }
            return data
        }
        let bodyText = String(data: data, encoding: .utf8) ?? ""
        switch http.statusCode {
        case 401:
            throw DriveUnauthorizedError()
        case 404:
            throw DriveNotFoundError()
        case 403 where bodyText.localizedCaseInsensitiveContains("storageQuotaExceeded")
            || bodyText.localizedCaseInsensitiveContains("quotaExceeded"):
            throw DriveQuotaExceededError()
        default:
            throw DriveAuthError.unknown
        }
    }

    // MARK: - Keychain

    private func saveTokens(_ tokens: StoredTokens) {
        guard let encoded = try? JSONEncoder().encode(tokens) else { return }
        deleteTokens()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: encoded,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    private func loadTokens() -> StoredTokens? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(StoredTokens.self, from: data)
    }

    private func deleteTokens() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Helpers

    @MainActor
    private func beginBackgroundTask() -> UIBackgroundTaskIdentifier {
        var taskId: UIBackgroundTaskIdentifier = .invalid
        taskId = UIApplication.shared.beginBackgroundTask(withName: "TrainFreeGoogleDriveBackup") {
            if taskId != .invalid {
                UIApplication.shared.endBackgroundTask(taskId)
                taskId = .invalid
            }
        }
        return taskId
    }

    private func escapeQuery(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'")
    }

    private func mapError(_ error: Error) -> DriveErrorKind {
        if error is DriveUnauthorizedError || error is DriveInvalidGrantError {
            return .authRequired
        }
        if error is DriveQuotaExceededError {
            return .quotaExceeded
        }
        if error is DriveNotFoundError {
            return .notFound
        }
        if let authError = error as? DriveAuthError {
            switch authError {
            case .authRequired: return .authRequired
            case .cancelled: return .cancelled
            case .notConfigured: return .notConfigured
            case .unknown: return .unknown
            }
        }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            switch nsError.code {
            case NSURLErrorNotConnectedToInternet,
                 NSURLErrorNetworkConnectionLost,
                 NSURLErrorTimedOut,
                 NSURLErrorCannotFindHost,
                 NSURLErrorCannotConnectToHost,
                 NSURLErrorDNSLookupFailed:
                return .offline
            default:
                break
            }
        }
        return .unknown
    }

    private static func generateCodeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64Url(Data(bytes))
    }

    private static func codeChallengeS256(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64Url(Data(digest))
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private struct DriveUnauthorizedError: Error {}
private struct DriveInvalidGrantError: Error {}
private struct DriveNotFoundError: Error {}
private struct DriveQuotaExceededError: Error {}

private enum DriveAuthError: Error {
    case authRequired
    case cancelled
    case notConfigured
    case unknown
}
