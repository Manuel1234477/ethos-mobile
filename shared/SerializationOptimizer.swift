import Foundation

/// #490: Optimizes data serialization performance for hot paths
class SerializationOptimizer {
    /// Cache for frequently serialized objects to avoid repeated encoding
    private static let cache = NSCache<NSString, NSData>()
    
    /// Encodes object with caching for hot-path serialization
    static func encode<T: Encodable>(_ value: T, key: String? = nil) -> Data? {
        let cacheKey = key ?? UUID().uuidString
        
        // Check cache first
        if let cached = cache.object(forKey: cacheKey as NSString) {
            return cached as Data
        }
        
        // Encode and cache
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = []  // Minimal formatting for performance
            let data = try encoder.encode(value)
            cache.setObject(data as NSData, forKey: cacheKey as NSString)
            return data
        } catch {
            return nil
        }
    }
    
    /// Decodes from data with optional caching
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        do {
            let decoder = JSONDecoder()
            return try decoder.decode(type, from: data)
        } catch {
            return nil
        }
    }
    
    /// Clears serialization cache when needed
    static func clearCache() {
        cache.removeAllObjects()
    }
}
