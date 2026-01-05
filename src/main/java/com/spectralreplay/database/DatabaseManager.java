package com.spectralreplay.database;

import com.spectralreplay.SpectralReplay;
import com.spectralreplay.model.PlayerAction;
import com.spectralreplay.model.ReplayFrame;
import com.spectralreplay.model.ReplayType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private void initialize() {
        try {
            File dataFolder = new File(plugin.getDataFolder(), "database.db");
            if (!dataFolder.getParentFile().exists()) {
                dataFolder.getParentFile().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
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
                    statement.execute("ALTER TABLE death_replays ADD COLUMN type VARCHAR(20) DEFAULT 'DEATH'");
                } catch (SQLException ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize database", e);
        }
    }

    public void saveReplay(UUID playerUUID, Location deathLocation, List<ReplayFrame> frames, ReplayType type) {
        String sql = "INSERT INTO death_replays (uuid, world, x, y, z, timestamp, replay_data, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ps.setString(2, deathLocation.getWorld().getName());
            ps.setDouble(3, deathLocation.getX());
            ps.setDouble(4, deathLocation.getY());
            ps.setDouble(5, deathLocation.getZ());
            ps.setLong(6, System.currentTimeMillis());
            ps.setBytes(7, serializeFrames(frames));
            ps.setString(8, type.name());
            
            ps.executeUpdate();
        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save replay", e);
        }
    }

    public List<ReplayData> getNearbyReplays(Location location, double radius, ReplayType type) {
        List<ReplayData> replays = new ArrayList<>();
        String sql;
        if (type != null) {
            sql = "SELECT * FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND type = ?";
        } else {
            sql = "SELECT * FROM death_replays WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?";
        }
        
        double xMin = location.getX() - radius;
        double xMax = location.getX() + radius;
        double zMin = location.getZ() - radius;
        double zMax = location.getZ() + radius;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setDouble(2, xMin);
            ps.setDouble(3, xMax);
            ps.setDouble(4, zMin);
            ps.setDouble(5, zMax);
            
            if (type != null) {
                ps.setString(6, type.name());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double y = rs.getDouble("y");
                    if (Math.abs(y - location.getY()) > 10) continue;

                    int id = rs.getInt("id");
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    Location loc = new Location(location.getWorld(), rs.getDouble("x"), y, rs.getDouble("z"));
                    byte[] data = rs.getBytes("replay_data");
                    String typeStr = rs.getString("type");
                    ReplayType rType = ReplayType.valueOf(typeStr != null ? typeStr : "DEATH");
                    
                    replays.add(new ReplayData(id, uuid, loc, deserializeFrames(data, loc.getWorld()), rType));
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

    private static final int MAGIC_NUMBER = 0x53524550;
    private static final int DATA_VERSION = 2;

    private byte[] serializeFrames(List<ReplayFrame> frames) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            dos.writeInt(MAGIC_NUMBER);
            dos.writeInt(DATA_VERSION);
            dos.writeInt(frames.size());
            for (ReplayFrame frame : frames) {
                Location loc = frame.getLocation();
                dos.writeDouble(loc.getX());
                dos.writeDouble(loc.getY());
                dos.writeDouble(loc.getZ());
                dos.writeFloat(loc.getYaw());
                dos.writeFloat(loc.getPitch());
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
                PlayerAction action = PlayerAction.values()[actionOrd];
                frames.add(new ReplayFrame(loc, action, sneaking, item, armor));
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
            return new byte[0];
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data);
             BukkitObjectInputStream bis = new BukkitObjectInputStream(is)) {
            return (ItemStack) bis.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static class ReplayData {
        public final int id;
        public final UUID uuid;
        public final Location location;
        public final List<ReplayFrame> frames;
        public final ReplayType type;

        public ReplayData(int id, UUID uuid, Location location, List<ReplayFrame> frames, ReplayType type) {
            this.id = id;
            this.uuid = uuid;
            this.location = location;
            this.frames = frames;
            this.type = type;
        }
    }
}
