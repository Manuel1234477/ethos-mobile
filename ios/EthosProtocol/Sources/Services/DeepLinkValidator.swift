import Foundation

/// #491: Validates deep link URL structure and routes before navigation
struct DeepLinkValidator {
    enum ValidationError: Error {
        case invalidURL
        case unknownRoute
        case missingRequiredParameter(String)
    }
    
    /// Validates deep link URL and returns the parsed route
    static func validate(url: URL) throws -> DeepLinkRoute {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            throw ValidationError.invalidURL
        }
        
        guard let host = components.host else {
            throw ValidationError.unknownRoute
        }
        
        // Parse route based on host and path
        let path = components.path
        switch (host, path) {
        case ("vault", _) where !path.isEmpty:
            return .vault(path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
        case ("accept", _):
            guard let token = components.queryItems?.first(where: { $0.name == "token" })?.value else {
                throw ValidationError.missingRequiredParameter("token")
            }
            return .accept(token: token)
        default:
            throw ValidationError.unknownRoute
        }
    }
}

enum DeepLinkRoute {
    case vault(String)
    case accept(token: String)
}
