package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * ApePack v2 format specification and core data structures.
 *
 * The v2 format is a ZIP archive containing:
 * - apepack.json: Pack metadata with format version, compression type, and checksums
 * - manifest.json: Structured file list with dependencies, conflicts, and config strategies
 * - data/*.xz: XZ/LZMA2-compressed data chunks (each chunk contains multiple files)
 * - chunks.json: Chunk index mapping files to their compressed chunks
 *
 * Key improvements over v1:
 * - XZ/LZMA2 compression (preset 9 for extreme ratio - 60-80% better than GZIP)
 * - Archive-level SHA-256 integrity verification
 * - Parallel extraction using multiple threads
 * - Incremental delta packs (only changed files)
 * - Dependency resolution with automatic handling
 * - Config conflict resolution strategies
 * - Resource and data pack support
 * - Backward compatibility with v1 format
 */
public class ApePackV2 {

    public static final int FORMAT_VERSION = 2;
    public static final String FORMAT_VERSION_KEY = "formatVersion";
    
    // Compression types
    public static final String COMPRESSION_XZ = "xz";
    public static final String COMPRESSION_GZIP = "gzip";
    public static final String COMPRESSION_DEFLATE = "deflate";
    public static final String COMPRESSION_NONE = "none";
    
    // XZ/LZMA2 compression preset (1-9, 9 is maximum compression ratio)
    public static final int XZ_COMPRESSION_PRESET = 9;
    
    // Chunk size for splitting large archives (64MB per chunk)
    public static final long CHUNK_SIZE = 64 * 1024 * 1024;
    
    // File types
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_CONFIG = "config";
    public static final String TYPE_SCRIPT = "script";
    public static final String TYPE_ASSET = "asset";
    public static final String TYPE_RESOURCE_PACK = "resourcepack";
    public static final String TYPE_DATA_PACK = "datapack";
    public static final String TYPE_WORLD = "world";
    
    // Config conflict resolution strategies
    public static final String STRATEGY_OVERWRITE = "overwrite";
    public static final String STRATEGY_MERGE = "merge";
    public static final String STRATEGY_SKIP = "skip";
    public static final String STRATEGY_BACKUP = "backup";
    public static final String STRATEGY_PROMPT = "prompt";
    
    // Pack types
    public static final String PACK_TYPE_FULL = "full";
    public static final String PACK_TYPE_DELTA = "delta";
    
    /**
     * Represents a file entry in the v2 manifest.
     */
    public static class V2Entry {
        public final String name;
        public final String type;
        public final String version;
        public final String sha256;
        public final String filePath;
        public final String installPath;
        public final long size;
        public final String configStrategy; // For config files
        public final List<String> dependencies; // Required mods
        public final List<String> conflicts; // Conflicting mods
        public final String chunkId; // Which compressed chunk contains this file
        
        V2Entry(String name, String type, String version, String sha256, String filePath, 
                String installPath, long size, String configStrategy, List<String> dependencies, 
                List<String> conflicts, String chunkId) {
            this.name = name;
            this.type = type;
            this.version = version;
            this.sha256 = sha256;
            this.filePath = filePath;
            this.installPath = installPath;
            this.size = size;
            this.configStrategy = configStrategy;
            this.dependencies = dependencies;
            this.conflicts = conflicts;
            this.chunkId = chunkId;
        }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("type", type);
            json.addProperty("version", version);
            json.addProperty("sha256", sha256);
            json.addProperty("file", filePath);
            json.addProperty("installPath", installPath);
            json.addProperty("size", size);
            
            if (configStrategy != null && !configStrategy.isEmpty()) {
                json.addProperty("configStrategy", configStrategy);
            }
            
            if (dependencies != null && !dependencies.isEmpty()) {
                JsonArray deps = new JsonArray();
                for (String dep : dependencies) deps.add(dep);
                json.add("dependencies", deps);
            }
            
            if (conflicts != null && !conflicts.isEmpty()) {
                JsonArray confs = new JsonArray();
                for (String conf : conflicts) confs.add(conf);
                json.add("conflicts", confs);
            }
            
            if (chunkId != null) {
                json.addProperty("chunk", chunkId);
            }
            
            return json;
        }
    }
    
    /**
     * Represents a compressed data chunk.
     */
    public static class ChunkInfo {
        public final String id;
        public final String compression;
        public final String sha256;
        public final long compressedSize;
        public final long uncompressedSize;
        public final List<String> files; // Files contained in this chunk
        
        ChunkInfo(String id, String compression, String sha256, long compressedSize, 
                  long uncompressedSize, List<String> files) {
            this.id = id;
            this.compression = compression;
            this.sha256 = sha256;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.files = files;
        }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("compression", compression);
            json.addProperty("sha256", sha256);
            json.addProperty("compressedSize", compressedSize);
            json.addProperty("uncompressedSize", uncompressedSize);
            
            JsonArray filesArray = new JsonArray();
            for (String file : files) filesArray.add(file);
            json.add("files", filesArray);
            
            return json;
        }
    }
    
    /**
     * Pack metadata for v2 format.
     */
    public static class V2PackInfo {
        public final String packName;
        public final String packVersion;
        public final String mcVersion;
        public final String modLoader; // e.g., "neoforge-26.1.2.122"
        public final String packType; // "full" or "delta"
        public final String compression;
        public final String archiveSha256;
        public final int modCount;
        public final int configCount;
        public final int scriptCount;
        public final int resourcePackCount;
        public final int dataPackCount;
        public final String generatedAt;
        public final String generatorVersion;
        public final Map<String, String> dependencies; // Required mods with minimum versions
        public final String deltaFromVersion; // For delta packs, the base version
        
        V2PackInfo(String packName, String packVersion, String mcVersion, String modLoader,
                   String packType, String compression, String archiveSha256, int modCount,
                   int configCount, int scriptCount, int resourcePackCount, int dataPackCount,
                   String generatedAt, String generatorVersion, Map<String, String> dependencies,
                   String deltaFromVersion) {
            this.packName = packName;
            this.packVersion = packVersion;
            this.mcVersion = mcVersion;
            this.modLoader = modLoader;
            this.packType = packType;
            this.compression = compression;
            this.archiveSha256 = archiveSha256;
            this.modCount = modCount;
            this.configCount = configCount;
            this.scriptCount = scriptCount;
            this.resourcePackCount = resourcePackCount;
            this.dataPackCount = dataPackCount;
            this.generatedAt = generatedAt;
            this.generatorVersion = generatorVersion;
            this.dependencies = dependencies;
            this.deltaFromVersion = deltaFromVersion;
        }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("formatVersion", FORMAT_VERSION);
            json.addProperty("packName", packName);
            json.addProperty("packVersion", packVersion);
            json.addProperty("mcVersion", mcVersion);
            json.addProperty("modLoader", modLoader);
            json.addProperty("packType", packType);
            json.addProperty("compression", compression);
            json.addProperty("archiveSha256", archiveSha256);
            json.addProperty("modCount", modCount);
            json.addProperty("configCount", configCount);
            json.addProperty("scriptCount", scriptCount);
            json.addProperty("resourcePackCount", resourcePackCount);
            json.addProperty("dataPackCount", dataPackCount);
            json.addProperty("generatedAt", generatedAt);
            json.addProperty("generatorVersion", generatorVersion);
            
            if (dependencies != null && !dependencies.isEmpty()) {
                JsonObject deps = new JsonObject();
                for (Map.Entry<String, String> entry : dependencies.entrySet()) {
                    deps.addProperty(entry.getKey(), entry.getValue());
                }
                json.add("dependencies", deps);
            }
            
            if (deltaFromVersion != null) {
                json.addProperty("deltaFrom", deltaFromVersion);
            }
            
            return json;
        }
    }
    
    /**
     * Result of v2 pack generation.
     */
    public static class V2GenerateResult {
        public final String outputFile;
        public final int modCount;
        public final int configCount;
        public final int scriptCount;
        public final int resourcePackCount;
        public final int dataPackCount;
        public final long totalSize;
        public final long compressedSize;
        public final double compressionRatio;
        public final boolean success;
        public final String errorMessage;
        
        V2GenerateResult(String outputFile, int modCount, int configCount, int scriptCount,
                        int resourcePackCount, int dataPackCount, long totalSize, 
                        long compressedSize, double compressionRatio, boolean success, 
                        String errorMessage) {
            this.outputFile = outputFile;
            this.modCount = modCount;
            this.configCount = configCount;
            this.scriptCount = scriptCount;
            this.resourcePackCount = resourcePackCount;
            this.dataPackCount = dataPackCount;
            this.totalSize = totalSize;
            this.compressedSize = compressedSize;
            this.compressionRatio = compressionRatio;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
