package bms.player.beatoraja.arena.client;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainLoader;
import bms.player.beatoraja.arena.lobby.Lobby;
import io.github.catizard.jlr2arenaex.enums.ClientToServer;
import io.github.catizard.jlr2arenaex.enums.ServerToClient;
import io.github.catizard.jlr2arenaex.network.*;
import bms.player.beatoraja.modmenu.ImGuiNotify;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Stack;
import java.util.Collections;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.utils.IOUtils;


public class WSClient extends WebSocketClient {
    private int skipNextMD5 = 0;
    private String skipTitle = null;
    private byte[] downloadBuf = null;
    private int downloadBufIndex = 0;
    private static Logger logger = LoggerFactory.getLogger(WSClient.class);
    private static String bmsDirectory = null;

    public WSClient(URI serverUri) {
        super(serverUri);
    }

    public WSClient(URI serverUri, Draft protocolDraft) {
        super(serverUri, protocolDraft);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        Client.connected.set(true);
        send(ClientToServer.CTS_USERNAME, Client.userName.get().getBytes());
        ImGuiNotify.info(String.format("Successfully connected to %s", Client.host));
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        try {
            parsePacket(bytes);
        } catch (IOException e) {
            e.printStackTrace();
            ImGuiNotify.error("parse packet: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(String s) {
        ImGuiNotify.info("Received: " + s);
        System.out.println("received " + s);
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        Client.destroy();
    }

    @Override
    public void onError(Exception e) {
        e.printStackTrace();
        ImGuiNotify.error(String.format("Connection to %s failed", Client.host));
        Client.destroy();
    }

    public void send(ClientToServer id, byte[] data) {
        if (this.isOpen() && Client.connected.get()) {
            super.send(PackUtil.concat(((byte) id.getValue()), data));
        }
    }

    private void parsePacket(ByteBuffer bytes) throws IOException {
        char id = ((char) bytes.get());
        ServerToClient ev = ServerToClient.from(id);
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data, 0, data.length);
        Value value;
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            value = unpacker.unpackValue();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        switch (ev) {
            case STC_PLAYERS_SCORE -> {
                ScoreMessage scoreMessage = new ScoreMessage(value);
                if (!Client.state.getPeers().containsKey(scoreMessage.getPlayer())) {
                    logger.error("[!] Player not found for score update");
                    return;
                }
                Client.state.getPeers().get(scoreMessage.getPlayer()).setScore(scoreMessage.getScore());
            }
            case STC_PLAYERS_READY_UPDATE -> {
                logger.info("[+] Got updated ready status");
                Client.updateReadyState(value);
            }
            case STC_SELECTED_CHART_RANDOM -> {
                logger.info("[+] Received selected bms");
                SelectedBMSMessage selectedBMSMessage = new SelectedBMSMessage(value);
                String md5 = selectedBMSMessage.getMd5();
		if (skipNextMD5 > 0){
		    skipNextMD5 = 0;
		    if (selectedBMSMessage.getTitle().equals(skipTitle)){
			skipTitle = null;
			return;
		    }
		    skipTitle = null;
		}
		if (md5.length() > 32){
		    skipNextMD5 = 1;
		    skipTitle = selectedBMSMessage.getTitle();
		}
                Client.state.getSelectedSongRemote().setTitle(selectedBMSMessage.getTitle());
                Client.state.getSelectedSongRemote().setMd5(md5);
                Client.state.getSelectedSongRemote().setArtist(selectedBMSMessage.getArtist());


                Lobby.addToLog(String.format("[#] Selected song: %s / %s", selectedBMSMessage.getTitle(), selectedBMSMessage.getArtist()));
                Lobby.addToLog(String.format("[#] Hash: %s", md5));

                // TODO: Setup item here
                //	hooks::maniac::itemModeEnabled = selectedBms.itemModeEnabled;
                //	if (selectedBms.itemModeEnabled) {
                //		gui::main_window::AddToLog("[#] Item mode enabled!");
                //	}

                if (!Client.state.getHost().equals(Client.state.getRemoteId())) {
	                logger.error("[+] Received random: {}", selectedBMSMessage.getRandomSeed());
                    Client.state.setRandomSeed(selectedBMSMessage.getRandomSeed());
                }
                SongDatabaseAccessor songDataAccessor = MainLoader.getScoreDatabaseAccessor();
                String[] queryHash = new String[1];
                queryHash[0] = md5;
                SongData[] songDatas = songDataAccessor.getSongDatas(queryHash);
                boolean missingChart = songDatas.length == 0;
                Client.state.setLobbySongData(missingChart ? null : songDatas[0]);
                if (missingChart) {
                    Lobby.addToLog("[!] You do not have this chart!");
                    send(ClientToServer.CTS_MISSING_CHART, "".getBytes());
                }
            }
            case STC_USERLIST -> {
                Client.updatePeerState(value);
            }
            case STC_CLIENT_REMOTE_ID -> Client.state.setRemoteId(new Address(value));
            case STC_MESSAGE -> {
                Message message = new Message(value);
		if (message.getMessage().equals("Hash check is disabled.")){
		    Client.strictHash = false;
		}
		else if (message.getMessage().equals("Hash check is enabled.")){
		    Client.strictHash = true;
		}
                if (message.isSystemMessage()) {
                    Lobby.addToLog(message.getMessage());
		    return;
                }
		if (!Client.state.getPeers().containsKey(message.getPlayer())) {
		    logger.info("[!] Player not found for message");
		    return;
		}
		Lobby.addToLogWithUser(message.getMessage(), message.getPlayer());
		return;
            }
            /*case STC_MISSING_CHART -> {}*/
            case STC_ITEM -> {
                // TODO: Item
            }
            case STC_ITEM_SETTINGS -> {
                // TODO: Item
            }
	case STC_FILE_TRANSFER -> {
	    if (data.length <4) return;
	    switch(data[3] >>> 4){
	    case 0x0:{
		// begin song download from server
		if (Client.acceptTransfer != 1) return;
		Client.acceptTransfer = 0;
		int fileSize = 0;
		fileSize += data[0] & 0xFF;
		fileSize += (int)(data[1] & 0xFF) << 8;
		fileSize += (int)(data[2] & 0xFF) << 16;
		fileSize += (int)(data[3] & 0x03) << 24;
		downloadBuf = new byte[fileSize];
		downloadBufIndex = 0;
		if (downloadBuf.length != downloadBufIndex){
		    Client.acceptTransfer = 2;
		    byte[] dat = new byte[5];
		    dat[0] = (byte) ClientToServer.CTS_FILE_TRANSFER.getValue();
		    dat[1] = (byte) downloadBufIndex;
		    dat[2] = (byte) (downloadBufIndex >>> 8);
		    dat[3] = (byte) (downloadBufIndex >>> 16);
		    dat[4] = (byte) (downloadBufIndex >>> 24);
		    dat[4] |= 0x10;
		    super.send(dat);
		}
		return;
	    }
	    case 0x1:{
		// send song directory to server
		if (Client.acceptTransfer != -1) return;
		Client.acceptTransfer = 0;
		if (Client.state.getCurrentSongData() == null){
		    return;
		}
		Path dir = Path.of(Client.state.getCurrentSongData().getPath());
		dir = dir.getParent().normalize();
		File f_dir = new File(dir.toString());
		if (f_dir == null || !f_dir.isDirectory()) {
		    return;
		}
		String dirname = f_dir.getName() + "/";
		ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
		TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(byteOutput);
		Stack<File> files = new Stack<>();
		Collections.addAll(files,f_dir.listFiles());
		File f = f_dir;
		TarArchiveEntry entry = new TarArchiveEntry(f, dirname);
		tarOutput.putArchiveEntry(entry);
		tarOutput.closeArchiveEntry();
		Path path_parent = f_dir.getParentFile().toPath();
		int file_size_sum = 0;
		while(!files.isEmpty() &&(f = files.pop())!= null){
		    dirname = path_parent.relativize(f.toPath()).toString();
		    if (f.isDirectory()){
			dirname = dirname + "/";
			entry = new TarArchiveEntry(f, dirname);
			tarOutput.putArchiveEntry(entry);
			tarOutput.closeArchiveEntry();
			File[] files_inner = f.listFiles();
			int file_count =  files_inner.length;
			for(int i = 0; i < file_count; ++i){
			    files.push(files_inner[i]);
			}
			continue;
		    }
		    entry = new TarArchiveEntry(f, dirname);
		    entry.setSize(f.length());
		    file_size_sum += f.length();
		    tarOutput.putArchiveEntry(entry);
		    tarOutput.write(IOUtils.toByteArray(new FileInputStream(f)));
		    tarOutput.closeArchiveEntry();
		}
		tarOutput.close();
		int size = byteOutput.size();
		if (size >= (1 << 27)){
		    Lobby.addToLog(String.format("file size limit exceeded"));
		}
		byte[] dat = new byte[5+size];
		dat[0] = (byte) ClientToServer.CTS_FILE_TRANSFER.getValue();
		dat[1] = (byte) size;
		dat[2] = (byte) (size >>> 8);
		dat[3] = (byte) (size >>> 16);
		dat[4] = (byte) (size >>> 24);
		if (this.isOpen() && Client.connected.get()) {
		    Lobby.addToLog("Sending bms to server...");
		    System.arraycopy(byteOutput.toByteArray(), 0, dat, 5, size);
		    super.send(dat);
		}
		return;
	    }
	    case 0x2:{	// receive partial segment from server
		if (Client.acceptTransfer != 2) return;
		int fileSize = 0;
		fileSize += data[0] & 0xFF;
		fileSize += (int)(data[1] & 0xFF) << 8;
		fileSize += (int)(data[2] & 0xFF) << 16;
		fileSize += (int)(data[3] & 0x03) << 24;
		if (data.length != fileSize + 4 || downloadBufIndex + fileSize >= downloadBuf.length){
		    System.out.println("Error in segment length");
		    System.out.println("index " + downloadBufIndex + ", segment  " + fileSize + ", buffer length " +
				       downloadBuf.length + ", message length " + data.length + 4);
		    Client.acceptTransfer = 0;
		    return;
		}
		System.arraycopy(data, 4, downloadBuf, downloadBufIndex, fileSize);
		downloadBufIndex += fileSize;
		byte[] dat = new byte[5];
		dat[0] = (byte) ClientToServer.CTS_FILE_TRANSFER.getValue();
		dat[1] = (byte) downloadBufIndex;
		dat[2] = (byte) (downloadBufIndex >>> 8);
		dat[3] = (byte) (downloadBufIndex >>> 16);
		dat[4] = (byte) (downloadBufIndex >>> 24);
		dat[4] |= 0x10;
		super.send(dat);
		return;
	    }
	    case 0x3:{	// receive final segment from server
		if (Client.acceptTransfer != 2) return;
		Client.acceptTransfer = 0;
		int fileSize = 0;
		fileSize += data[0] & 0xFF;
		fileSize += (int)(data[1] & 0xFF) << 8;
		fileSize += (int)(data[2] & 0xFF) << 16;
		fileSize += (int)(data[3] & 0x03) << 24;
		if (data.length != fileSize + 4 || downloadBufIndex + fileSize != downloadBuf.length){
		    System.out.println("index " + downloadBufIndex + ", segment  " + fileSize + ", buffer length " +
				       downloadBuf.length + ", message length " + data.length + 4);
		    return;
		}
		System.arraycopy(data, 4, downloadBuf, downloadBufIndex, fileSize);
		downloadBufIndex += fileSize;
		TarArchiveInputStream tarIn = new TarArchiveInputStream(new ByteArrayInputStream(downloadBuf));
		File f = new File("arena_download");
		f.mkdir();
		TarArchiveEntry entry = null;
		int file_size_sum = 0;
		bmsDirectory = null;
		while((entry  = tarIn.getNextEntry()) != null){
		    String name = "arena_download/" + entry.getName();
		    f = new File(name);
		    if (!f.toPath().startsWith("arena_download")) {
			System.out.println("Rejecting file: " +name);
			continue;
		    }
		    if (entry.isDirectory()){
			f.mkdir();
			if (bmsDirectory == null)
			    bmsDirectory = name;
			continue;
		    }
		    if (!entry.isFile()){
			System.out.println("Unexpected entry in tar is not a file: " + entry.getName());
			continue;
		    }
		    f = new File(name);
		    FileOutputStream fileOut = new FileOutputStream(f);
		    byte[] fbuf = new byte[(int) entry.getSize()];
		    if (tarIn.read(fbuf, 0, (int) entry.getSize()) != entry.getSize())
			System.out.println("Failed to read entry: " + entry.getName());
		    fileOut.write(fbuf);
		    fileOut.close();
		    file_size_sum += entry.getSize();
		}
		MainController.pushOneShotAfterRenderTask(main -> {
			main.updateSong(bmsDirectory,true);
			String md5 = Client.state.getSelectedSongRemote().getMd5();
			SongData[] songDatas = main.getSongDatabase().getSongDatas(new String[]{md5});
			for (int i = 0; i < 5; ++i){
			    try{
				Thread.sleep(100);
			    }
			    catch (Exception e){
			    }
			    songDatas = main.getSongDatabase().getSongDatas(new String[]{md5});
			    if (songDatas != null && songDatas.length > 0) {
				Client.state.setLobbySongData(songDatas[0]);
				break;
			    }
			}
		    });
		Lobby.addToLog(String.format("bms download complete"));
		return;
	    }
	    case 0x4:
	    case 0x5:
	    case 0x6:
	    case 0x7:
	    case 0x8:
	    case 0x9:
	    case 0xA:
	    case 0xB:
	    case 0xC:
	    case 0xD:
	    case 0xE:
	    case 0xF:
	    }
	    return;
	}
            default -> ImGuiNotify.warning("unexpected S->C message id: " + id);
        }
    }
}
