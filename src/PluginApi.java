
package no.polaric.aprsdb;
import no.polaric.aprsd.*;
 
 
 
public interface PluginApi {
    public Logfile log();
    public MyDBSession getDB() throws DBSession.SessionError;
    public MyDBSession getDB(boolean autocommit) throws DBSession.SessionError;
    public void saveItem(TrackerPoint tp);
}
