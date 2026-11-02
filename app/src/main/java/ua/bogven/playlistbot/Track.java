package ua.bogven.playlistbot;

/** Один трек, знайдений у безкоштовному легальному джерелі. */
public class Track {
    public String title = "";
    public String artist = "";
    public String downloadUrl = "";
    public String streamUrl = "";
    public String license = "";
    public String pageUrl = "";
    public String source = "";
    public boolean selected = true;

    public String key() {
        return (artist + "|" + title).toLowerCase().trim();
    }

    @Override
    public String toString() {
        return artist + " — " + title;
    }
}
