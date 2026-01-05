package com.spectralreplay.database;

import com.spectralreplay.SpectralReplay;
import com.spectralreplay.model.PlayerAction;
import com.spectralreplay.model.ReplayFrame;
import com.spectralreplay.model.ReplayType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final SpectralReplay plugin;
    private Connection connection;

    public DatabaseManager(SpectralReplay plugin) {
        this.plugin = plugin;
        initialize();
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                File dataFolder = new File(plugin.getDataFolder(), "database.db");
                if (!dataFolder.getParentFile().exists()) {
                    dataFolder.getParentFile().mkdirs();
                }
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found", e);
            }
        }
        return connection;
    }

    private void initialize() {
        try {
            try (Statement statement = getConnection().createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS death_replays (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "uuid VARCHAR(36) NOT NULL," +
                        "world VARCHAR(50) NOT NULL," +
                        "x DOUBLE NOT NULL," +
                        "y DOUBLE NOT NULL," +
                        "z DOUBLE NOT NULL," +
                        "timestamp LONG NOT NULL," +
                        "replay_data BLOB NOT NULL," +
                        "type VARCHAR(20) DEFAULT 'DEATH'" +
                        ")");
                
                try {
                    // Check if column exists before adding
                    DatabaseMetaData md = connection.getMetaData();
                    ResultSet rs = md.getColumns(null, null, "death_replays", "type");
                    if (!rs.next()) {
                        statement.execute("ALTER TABLE death_replays ADD COLUMN type VARCHAR(20) DEFAULT 'DEATH'");
                    }
                    rs.close();
                } catch (SQLException ignored) {
                    plugin.getLogger().warning("Failed to check/add 'type' column: " + ignored.getMessage());
                }

                statement.execute("CREATE TABLE IF NOT EXISTS placed_replays (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "replay_id INTEGER NOT NULL," +
                        "world VARCHAR(50) NOT NULL," +
                        "x DOUBLE NOT NULL," +
                        "y DOUBLE NOT NULL," +
                        "z DOUBLE NOT NULL," +
                        "yaw FLOAT NOT NULL," +
                        "pitch FLOAT NOT NULL" +
                        ")");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize database", e);
            // Disable plugin if database fails to initialize to prevent further errors
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    public void saveReplay(UUID playerUUID, Location deathLocation, List<ReplayFrame> frames, ReplayType type, long timestamp) {
        String sql = "INSERT INTO death_replays (uuid, world, x, y, z, timestamp, replay_data, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ps.setString(2, deathLocation.getWorld().getName());
            ps.setDouble(3, deathLocation.getX());
            ps.setDouble(4, deathLocation.getY());
            ps.setDouble(5, deathLocation.getZ());
            ps.setLong(6, timestamp);
            ps.setBytes(7, serializeFrames(frames));
            ps.setString(8, type.name());
            
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save replay", e);
        }
    }

    public void saveReplay(UUID playerUUID, Location deathLocation, List<ReplayFrame> frames, ReplayType type) {
        saveReplay(playerUUID, deathLocation, frames, type, System.currentTimeMillis());
    }

    public List<ReplayData> getReplaysByTimestamp(long timestamp) {
        List<ReplayData> replays = new ArrayList<>();
        String sql = "SELECT * FROM death_replays WHERE timestamp = ?";
        
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, timestamp);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String worldName = rs.getString("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) continue;
                    
                    Location loc = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                    byte[] data = rs.getBytes("replay_data");
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    replays.add(new ReplayData(id, uuid, loc, deserializeFrames(data, world), rType, timestamp));
                }
            }
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load replays by timestamp", e);
        }
        return replays;
    }

    public List<ReplayData> getNearbyReplayMeta(Location location, double radius, ReplayType type) {
        List<ReplayData> replays = new ArrayList<>();
        String sql;
        if (type != null) {
            sql = "SELECT id, uuid, world, x, y, z, type, timestamp FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ? AND type = ?";
        } else {
            sql = "SELECT id, uuid, world, x, y, z, type, timestamp FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ?";
        }
        
        double xMin = location.getX() - radius;
        double xMax = location.getX() + radius;
        double zMin = location.getZ() - radius;
        double zMax = location.getZ() + radius;
        double yMin = location.getY() - radius;
        double yMax = location.getY() + radius;

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setDouble(2, xMin);
            ps.setDouble(3, xMax);
            ps.setDouble(4, zMin);
            ps.setDouble(5, zMax);
            ps.setDouble(6, yMin);
            ps.setDouble(7, yMax);
            
            if (type != null) {
                ps.setString(8, type.name());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    double y = rs.getDouble("y");
                    Location loc = new Location(location.getWorld(), rs.getDouble("x"), y, rs.getDouble("z"));
                    
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    long timestamp = rs.getLong("timestamp");
                    replays.add(new ReplayData(id, uuid, loc, null, rType, timestamp));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load replay metadata", e);
        }
        return replays;
    }

    public List<ReplayFrame> getReplayFrames(int id) {
        String sql = "SELECT replay_data, world FROM death_replays WHERE id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("replay_data");
                    String worldName = rs.getString("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        return deserializeFrames(data, world);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load replay frames", e);
        }
        return new ArrayList<>();
    }

    public List<ReplayData> getNearbyReplays(Location location, double radius, ReplayType type) {
        List<ReplayData> replays = new ArrayList<>();
        String sql;
        if (type != null) {
            sql = "SELECT * FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ? AND type = ?";
        } else {
            sql = "SELECT * FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ?";
        }
        
        double xMin = location.getX() - radius;
        double xMax = location.getX() + radius;
        double zMin = location.getZ() - radius;
        double zMax = location.getZ() + radius;
        double yMin = location.getY() - radius;
        double yMax = location.getY() + radius;

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setDouble(2, xMin);
            ps.setDouble(3, xMax);
            ps.setDouble(4, zMin);
            ps.setDouble(5, zMax);
            ps.setDouble(6, yMin);
            ps.setDouble(7, yMax);
            
            if (type != null) {
                ps.setString(8, type.name());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    double y = rs.getDouble("y");
                    Location loc = new Location(location.getWorld(), rs.getDouble("x"), y, rs.getDouble("z"));
                    byte[] data = rs.getBytes("replay_data");
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    long timestamp = rs.getLong("timestamp");
                    replays.add(new ReplayData(id, uuid, loc, deserializeFrames(data, loc.getWorld()), rType, timestamp));
                }
            }
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load replays", e);
        }
        return replays;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int savePlacedReplay(int replayId, Location location) {
        String sql = "INSERT INTO placed_replays (replay_id, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, replayId);
            ps.setString(2, location.getWorld().getName());
            ps.setDouble(3, location.getX());
            ps.setDouble(4, location.getY());
            ps.setDouble(5, location.getZ());
            ps.setFloat(6, location.getYaw());
            ps.setFloat(7, location.getPitch());
            ps.executeUpdate();
            
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save placed replay", e);
        }
        return -1;
    }

    public void deletePlacedReplay(int id) {
        String sql = "DELETE FROM placed_replays WHERE id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete placed replay", e);
        }
    }

    public List<PlacedReplay> getAllPlacedReplays() {
        List<PlacedReplay> replays = new ArrayList<>();
        String sql = "SELECT * FROM placed_replays";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int replayId = rs.getInt("replay_id");
                String worldName = rs.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;
                
                Location loc = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));
                replays.add(new PlacedReplay(id, replayId, loc));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load placed replays", e);
        }
        return replays;
    }

    public ReplayData getReplayById(int id) {
        String sql = "SELECT * FROM death_replays WHERE id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String worldName = rs.getString("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) return null;
                    
                    Location loc = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                    byte[] data = rs.getBytes("replay_data");
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    long timestamp = rs.getLong("timestamp");
                    return new ReplayData(id, uuid, loc, deserializeFrames(data, world), rType, timestamp);
                }
            }
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load replay by id", e);
        }
        return null;
    }

    public List<ReplayData> getRecentReplays(int limit) {
        List<ReplayData> replays = new ArrayList<>();
        String sql = "SELECT * FROM death_replays ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String worldName = rs.getString("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) continue;
                    
                    Location loc = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                    byte[] data = rs.getBytes("replay_data");
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    long timestamp = rs.getLong("timestamp");
                    replays.add(new ReplayData(id, uuid, loc, deserializeFrames(data, world), rType, timestamp));
                }
            }
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load recent replays", e);
        }
        return replays;
    }

    private static final int MAGIC_NUMBER = 0x53524550;
    private static final int DATA_VERSION = 2;

    public boolean deleteReplay(int id) {
        // First, get the replay to check if it's a PVP replay
        ReplayData replay = getReplayById(id);
        if (replay == null) return false;

        String sql = "DELETE FROM death_replays WHERE id = ?";
        
        // If it's PVP, we want to delete the partner replay too (same timestamp)
        if (replay.type == ReplayType.PVP) {
            sql = "DELETE FROM death_replays WHERE timestamp = ?";
        }

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            if (replay.type == ReplayType.PVP) {
                ps.setLong(1, replay.timestamp);
            } else {
                ps.setInt(1, id);
            }
            
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete replay", e);
            return false;
        }
    }

    private byte[] serializeFrames(List<ReplayFrame> frames) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            dos.writeInt(MAGIC_NUMBER);
            dos.writeInt(DATA_VERSION);
            dos.writeInt(frames.size());
            for (ReplayFrame frame : frames) {
                dos.writeDouble(frame.getX());
                dos.writeDouble(frame.getY());
                dos.writeDouble(frame.getZ());
                dos.writeFloat(frame.getYaw());
                dos.writeFloat(frame.getPitch());
                dos.writeByte(frame.getAction().ordinal());
                dos.writeBoolean(frame.isSneaking());

                byte[] itemBytes = serializeItem(frame.getItemInHand());
                dos.writeInt(itemBytes.length);
                if (itemBytes.length > 0) {
                    dos.write(itemBytes);
                }

                ItemStack[] armor = frame.getArmor();
                if (armor == null) {
                    dos.writeInt(0);
                } else {
                    dos.writeInt(armor.length);
                    for (ItemStack armorPiece : armor) {
                        byte[] armorBytes = serializeItem(armorPiece);
                        dos.writeInt(armorBytes.length);
                        if (armorBytes.length > 0) {
                            dos.write(armorBytes);
                        }
                    }
                }
            }
            return baos.toByteArray();
        }
    }

    private List<ReplayFrame> deserializeFrames(byte[] data, org.bukkit.World world) throws IOException {
        List<ReplayFrame> frames = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {
            
            if (dis.available() < 4) return frames;
            int magic = dis.readInt();
            if (magic != MAGIC_NUMBER) {
                plugin.getLogger().warning("Invalid replay data format (Magic Number mismatch). Skipping.");
                return frames;
            }

            int version = dis.readInt();
            if (version != DATA_VERSION) {
                plugin.getLogger().warning("Skipping replay with incompatible data version: " + version + " (Expected: " + DATA_VERSION + ")");
                return frames;
            }

            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                try {
                    double x = dis.readDouble();
                    double y = dis.readDouble();
                    double z = dis.readDouble();
                    float yaw = dis.readFloat();
                    float pitch = dis.readFloat();
                    int actionOrd = dis.readByte();
                    boolean sneaking = dis.readBoolean();

                    int itemLen = dis.readInt();
                    ItemStack item = null;
                    if (itemLen > 0) {
                        byte[] itemBytes = new byte[itemLen];
                        dis.readFully(itemBytes);
                        item = deserializeItem(itemBytes);
                    }

                    int armorCount = dis.readInt();
                    ItemStack[] armor = new ItemStack[armorCount];
                    for (int j = 0; j < armorCount; j++) {
                        int armorLen = dis.readInt();
                        if (armorLen > 0) {
                            byte[] armorBytes = new byte[armorLen];
                            dis.readFully(armorBytes);
                            armor[j] = deserializeItem(armorBytes);
                        } else {
                            armor[j] = null;
                        }
                    }

                    Location loc = new Location(world, x, y, z, yaw, pitch);
                    PlayerAction action = PlayerAction.values()[Math.min(Math.max(actionOrd, 0), PlayerAction.values().length - 1)];
                    frames.add(new ReplayFrame(loc, action, sneaking, item, armor));
                } catch (EOFException e) {
                    plugin.getLogger().warning("Unexpected end of file while reading replay frames. Replay might be truncated.");
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("Error reading a frame: " + e.getMessage());
                    // Continue to next frame if possible, or break? 
                    // If structure is lost, we should probably break.
                    break;
                }
            }
        }
        return frames;
    }

    private byte[] serializeItem(ItemStack item) {
        if (item == null) return new byte[0];
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
             BukkitObjectOutputStream bos = new BukkitObjectOutputStream(os)) {
            bos.writeObject(item);
            return os.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to serialize item: " + item.getType(), e);
            return new byte[0];
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data);
             BukkitObjectInputStream bis = new BukkitObjectInputStream(is)) {
            return (ItemStack) bis.readObject();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item", e);
            return null;
        }
    }

    public static class ReplayData {
        public final int id;
        public final UUID uuid;
        public final Location location;
        public final List<ReplayFrame> frames;
        public final ReplayType type;
        public final long timestamp;

        public ReplayData(int id, UUID uuid, Location location, List<ReplayFrame> frames, ReplayType type) {
            this(id, uuid, location, frames, type, 0);
        }

        public ReplayData(int id, UUID uuid, Location location, List<ReplayFrame> frames, ReplayType type, long timestamp) {
            this.id = id;
            this.uuid = uuid;
            this.location = location;
            this.frames = frames;
            this.type = type;
            this.timestamp = timestamp;
        }
    }

    public static class PlacedReplay {
        public final int id;
        public final int replayId;
        public final Location location;

        public PlacedReplay(int id, int replayId, Location location) {
            this.id = id;
            this.replayId = replayId;
            this.location = location;
        }
    }
}
