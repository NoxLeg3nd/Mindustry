package io.anuke.mindustry.net;

import io.anuke.arc.Core;
import io.anuke.arc.collection.*;
import io.anuke.arc.function.BiConsumer;
import io.anuke.arc.function.Consumer;
import io.anuke.arc.util.*;
import io.anuke.arc.util.pooling.Pools;
import io.anuke.mindustry.core.Platform;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.net.Packets.*;
import io.anuke.mindustry.net.Streamable.StreamBuilder;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import static io.anuke.mindustry.Vars.*;

@SuppressWarnings("unchecked")
public class Net{
    private static boolean server;
    private static boolean active;
    private static boolean clientLoaded;
    private static Array<Object> packetQueue = new Array<>();
    private static ObjectMap<Class<?>, Consumer> clientListeners = new ObjectMap<>();
    private static ObjectMap<Class<?>, BiConsumer<Integer, Object>> serverListeners = new ObjectMap<>();
    private static ClientProvider clientProvider;
    private static ServerProvider serverProvider;
    private static IntMap<StreamBuilder> streams = new IntMap<>();

    /** Display a network error. Call on the graphics thread. */
    public static void showError(Throwable e){

        if(!headless){

            Throwable t = e;
            while(t.getCause() != null){
                t = t.getCause();
            }

            String error = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            String type = t.getClass().toString().toLowerCase();
            boolean isError = false;

            if(e instanceof BufferUnderflowException || e instanceof BufferOverflowException){
                error = Core.bundle.get("error.io");
            }else if(error.equals("mismatch")){
                error = Core.bundle.get("error.mismatch");
            }else if(error.contains("port out of range") || error.contains("invalid argument") || (error.contains("invalid") && error.contains("address")) || Strings.parseException(e, true).contains("address associated")){
                error = Core.bundle.get("error.invalidaddress");
            }else if(error.contains("connection refused") || error.contains("route to host") || type.contains("unknownhost")){
                error = Core.bundle.get("error.unreachable");
            }else if(type.contains("timeout")){
                error = Core.bundle.get("error.timedout");
            }else if(error.equals("alreadyconnected") || error.contains("connection is closed")){
                error = Core.bundle.get("error.alreadyconnected");
            }else if(!error.isEmpty()){
                error = Core.bundle.get("error.any") + "\n" + Strings.parseException(e, true);
                isError = true;
            }

            if(isError){
                ui.showError(Core.bundle.format("connectfail", error));
            }else{
                ui.showText("", Core.bundle.format("connectfail", error));
            }
            ui.loadfrag.hide();

            if(Net.client()){
                netClient.disconnectQuietly();
            }
        }

        Log.err(e);
    }

    /**
     * Sets the client loaded status, or whether it will recieve normal packets from the server.
     */
    public static void setClientLoaded(boolean loaded){
        clientLoaded = loaded;

        if(loaded){
            //handle all packets that were skipped while loading
            for(int i = 0; i < packetQueue.size; i++){
                handleClientReceived(packetQueue.get(i));
            }
        }
        //clear inbound packet queue
        packetQueue.clear();
    }

    /**
     * Connect to an address.
     */
    public static void connect(String ip, int port, Runnable success){
        try{
            if(!active){
                clientProvider.connect(ip, port, success);
                active = true;
                server = false;
            }else{
                throw new IOException("alreadyconnected");
            }
        }catch(IOException e){
            showError(e);
        }
    }

    /**
     * Host a server at an address.
     */
    public static void host(int port) throws IOException{
        serverProvider.host(port);
        active = true;
        server = true;

        Time.runTask(60f, Platform.instance::updateRPC);
    }

    /**
     * Closes the server.
     */
    public static void closeServer(){
        for(NetConnection con : getConnections()){
            Call.onKick(con.id, KickReason.serverClose);
        }

        serverProvider.close();
        server = false;
        active = false;
    }

    public static void reset(){
        closeServer();
        netClient.disconnectNoReset();
    }

    public static void disconnect(){
        clientProvider.disconnect();
        server = false;
        active = false;
    }

    public static byte[] compressSnapshot(byte[] input){
        return serverProvider.compressSnapshot(input);
    }

    public static byte[] decompressSnapshot(byte[] input, int size){
        return clientProvider.decompressSnapshot(input, size);
    }

    /**
     * Starts discovering servers on a different thread.
     * Callback is run on the main libGDX thread.
     */
    public static void discoverServers(Consumer<Host> cons, Runnable done){
        clientProvider.discover(cons, done);
    }

    /**
     * Returns a list of all connections IDs.
     */
    public static Iterable<NetConnection> getConnections(){
        return (Iterable<NetConnection>)serverProvider.getConnections();
    }

    /**
     * Returns a connection by ID
     */
    public static NetConnection getConnection(int id){
        return serverProvider.getByID(id);
    }

    /**
     * Send an object to all connected clients, or to the server if this is a client.
     */
    public static void send(Object object, SendMode mode){
        if(server){
            if(serverProvider != null) serverProvider.send(object, mode);
        }else{
            if(clientProvider != null) clientProvider.send(object, mode);
        }
    }

    /**
     * Send an object to a certain client. Server-side only
     */
    public static void sendTo(int id, Object object, SendMode mode){
        serverProvider.sendTo(id, object, mode);
    }

    /**
     * Send an object to everyone EXCEPT certain client. Server-side only
     */
    public static void sendExcept(int id, Object object, SendMode mode){
        serverProvider.sendExcept(id, object, mode);
    }

    /**
     * Send a stream to a specific client. Server-side only.
     */
    public static void sendStream(int id, Streamable stream){
        serverProvider.sendStream(id, stream);
    }

    /**
     * Sets the net clientProvider, e.g. what handles sending, recieving and connecting to a server.
     */
    public static void setClientProvider(ClientProvider provider){
        Net.clientProvider = provider;
    }

    /**
     * Sets the net serverProvider, e.g. what handles hosting a server.
     */
    public static void setServerProvider(ServerProvider provider){
        Net.serverProvider = provider;
    }

    /**
     * Registers a client listener for when an object is recieved.
     */
    public static <T> void handleClient(Class<T> type, Consumer<T> listener){
        clientListeners.put(type, listener);
    }

    /**
     * Registers a server listener for when an object is recieved.
     */
    public static <T> void handleServer(Class<T> type, BiConsumer<Integer, T> listener){
        serverListeners.put(type, (BiConsumer<Integer, Object>)listener);
    }

    /**
     * Call to handle a packet being recieved for the client.
     */
    public static void handleClientReceived(Object object){

        if(object instanceof StreamBegin){
            StreamBegin b = (StreamBegin)object;
            streams.put(b.id, new StreamBuilder(b));
        }else if(object instanceof StreamChunk){
            StreamChunk c = (StreamChunk)object;
            StreamBuilder builder = streams.get(c.id);
            if(builder == null){
                throw new RuntimeException("Recieved stream chunk without a StreamBegin beforehand!");
            }
            builder.add(c.data);
            if(builder.isDone()){
                streams.remove(builder.id);
                handleClientReceived(builder.build());
            }
        }else if(clientListeners.get(object.getClass()) != null){

            if(clientLoaded || ((object instanceof Packet) && ((Packet)object).isImportant())){
                if(clientListeners.get(object.getClass()) != null)
                    clientListeners.get(object.getClass()).accept(object);
                Pools.free(object);
            }else if(!((object instanceof Packet) && ((Packet)object).isUnimportant())){
                packetQueue.add(object);
            }else{
                Pools.free(object);
            }
        }else{
            Log.err("Unhandled packet type: '{0}'!", object);
        }
    }

    /**
     * Call to handle a packet being recieved for the server.
     */
    public static void handleServerReceived(int connection, Object object){

        if(serverListeners.get(object.getClass()) != null){
            if(serverListeners.get(object.getClass()) != null)
                serverListeners.get(object.getClass()).accept(connection, object);
            Pools.free(object);
        }else{
            Log.err("Unhandled packet type: '{0}'!", object.getClass());
        }
    }

    /**
     * Pings a host in an new thread. If an error occured, failed() should be called with the exception.
     */
    public static void pingHost(String address, int port, Consumer<Host> valid, Consumer<Exception> failed){
        clientProvider.pingHost(address, port, valid, failed);
    }

    /**
     * Update client ping.
     */
    public static void updatePing(){
        clientProvider.updatePing();
    }

    /**
     * Get the client ping. Only valid after updatePing().
     */
    public static int getPing(){
        return server() ? 0 : clientProvider.getPing();
    }

    /**
     * Whether the net is active, e.g. whether this is a multiplayer game.
     */
    public static boolean active(){
        return active;
    }

    /**
     * Whether this is a server or not.
     */
    public static boolean server(){
        return server && active;
    }

    /**
     * Whether this is a client or not.
     */
    public static boolean client(){
        return !server && active;
    }

    public static void dispose(){
        if(clientProvider != null) clientProvider.dispose();
        if(serverProvider != null) serverProvider.close();
        clientProvider = null;
        serverProvider = null;
        server = false;
        active = false;
    }

    public enum SendMode{
        tcp, udp
    }

    /** Client implementation. */
    public interface ClientProvider{
        /** Connect to a server. */
        void connect(String ip, int port, Runnable success) throws IOException;

        /** Send an object to the server. */
        void send(Object object, SendMode mode);

        /** Update the ping. Should be done every second or so. */
        void updatePing();

        /** Get ping in milliseconds. Will only be valid after a call to updatePing. */
        int getPing();

        /** Disconnect from the server. */
        void disconnect();

        /** Decompress an input snapshot byte array. */
        byte[] decompressSnapshot(byte[] input, int size);

        /**
         * Discover servers. This should run the callback regardless of whether any servers are found. Should not block.
         * Callback should be run on libGDX main thread.
         * @param done is the callback that should run after discovery.
         */
        void discover(Consumer<Host> callback, Runnable done);

        /** Ping a host. If an error occured, failed() should be called with the exception. */
        void pingHost(String address, int port, Consumer<Host> valid, Consumer<Exception> failed);

        /** Close all connections. */
        void dispose();
    }

    /** Server implementation. */
    public interface ServerProvider{
        /** Host a server at specified port. */
        void host(int port) throws IOException;

        /** Sends a large stream of data to a specific client. */
        default void sendStream(int id, Streamable stream){
            NetConnection connection = getByID(id);
            if(connection == null) return;
            try{
                int cid;
                StreamBegin begin = new StreamBegin();
                begin.total = stream.stream.available();
                begin.type = Registrator.getID(stream.getClass());
                connection.send(begin, SendMode.tcp);
                cid = begin.id;

                while(stream.stream.available() > 0){
                    byte[] bytes = new byte[Math.min(512, stream.stream.available())];
                    stream.stream.read(bytes);

                    StreamChunk chunk = new StreamChunk();
                    chunk.id = cid;
                    chunk.data = bytes;
                    connection.send(chunk, SendMode.tcp);
                }
            }catch(IOException e){
                throw new RuntimeException(e);
            }
        }

        default void send(Object object, SendMode mode){
            for(NetConnection con : getConnections()){
                con.send(object, mode);
            }
        }

        default void sendTo(int id, Object object, SendMode mode){
            NetConnection conn = getByID(id);
            if(conn == null){
                Log.err("Failed to find connection with ID {0}.", id);
                return;
            }
            conn.send(object, mode);
        }

        default void sendExcept(int id, Object object, SendMode mode){
            for(NetConnection con : getConnections()){
                if(con.id != id){
                    con.send(object, mode);
                }
            }
        }

        /** Close the server connection. */
        void close();

        /** Compress an input snapshot byte array. */
        byte[] compressSnapshot(byte[] input);

        /** Return all connected users. */
        Iterable<? extends NetConnection> getConnections();

        /** Returns a connection by ID. */
        NetConnection getByID(int id);
    }
}
