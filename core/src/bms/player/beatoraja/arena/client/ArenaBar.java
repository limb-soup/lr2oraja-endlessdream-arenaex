package bms.player.beatoraja.arena.client;

import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.DirectoryBar;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class ArenaBar extends DirectoryBar {
    private SongData songData;

    public ArenaBar(MusicSelector selector, SongData songData) {
        super(selector );
        this.songData = songData;
    }

    @Override
    public void updateFolderStatus() {
	if (Client.strictHash){
	    updateFolderStatus(new SongData[]{songData});
	}
	List<SongData> list = new ArrayList<>(Arrays.asList(selector.getSongDatabase().getSongDatasByText(songData.getTitle())));
	SongData[] fromText = list.stream().
	    filter(t -> t.getTitle().equals(songData.getTitle()) && t.getArtist().equals(songData.getArtist())).
	    toArray(size -> new SongData[size]);
        updateFolderStatus(fromText);
    }

    @Override
    public Bar[] getChildren() {
	if (Client.strictHash){
	    return SongBar.toSongBarArray(new SongData[]{songData});
	}
	boolean found = false;
	List<SongData> list = new ArrayList<>(Arrays.asList(selector.getSongDatabase().getSongDatasByText(songData.getTitle())));
	SongData[] fromText = list.stream().
	    filter(t -> t.getTitle().equals(songData.getTitle()) && t.getArtist().equals(songData.getArtist())).
	    toArray(size -> new SongData[size]);
        return SongBar.toSongBarArray(fromText);
    }

    @Override
    public String getTitle() {
        return "Arena";
    }
}
