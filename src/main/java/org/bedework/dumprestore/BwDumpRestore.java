/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.bedework.dumprestore;

import org.bedework.access.PrivilegeDefs;
import org.bedework.base.exc.BedeworkAccessException;
import org.bedework.caldav.util.sharing.AccessType;
import org.bedework.calfacade.BwCollection;
import org.bedework.calfacade.BwEvent;
import org.bedework.calfacade.BwLocation;
import org.bedework.calfacade.BwXproperty;
import org.bedework.calfacade.configs.DumpRestoreProperties;
import org.bedework.calfacade.svc.CalSvcIPars;
import org.bedework.calfacade.svc.EventInfo;
import org.bedework.calsvci.CalSvcFactoryDefault;
import org.bedework.calsvci.CalSvcI;
import org.bedework.calsvci.CollectionsI.CheckSubscriptionResult;
import org.bedework.calsvci.RestoreIntf.FixAliasResult;
import org.bedework.dumprestore.dump.Dump;
import org.bedework.dumprestore.restore.Restore;
import org.bedework.indexer.BwIndexCtlMBean;
import org.bedework.util.jmx.ConfBase;
import org.bedework.util.jmx.MBeanUtil;
import org.bedework.util.misc.Util;
import org.bedework.util.timezones.DateTimeUtil;
import org.bedework.util.xml.FromXml;
import org.bedework.util.xml.XmlUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author douglm
 *
 */
public class BwDumpRestore extends ConfBase<DumpRestorePropertiesImpl>
        implements BwDumpRestoreMBean {
  /* Name of the directory holding the config data */
  private static final String confDirName = "bwengine";

  private List<AliasInfo> externalSubs;

  /* Collections marked as aliases. We may need to fix sharing
   */
  private Map<String, AliasEntry> aliasInfo = new HashMap<>();

  private boolean allowRestore;

  private boolean fixAliases;

  private boolean lowercaseAccounts;

  private boolean newRestoreFormat;
  
  private boolean newDumpFormat;

  private String curSvciOwner;

  private CalSvcI svci;

  private class RestoreThread extends Thread {
    InfoLines infoLines = new InfoLines();

    RestoreThread() {
      super("RestoreData");
    }

    @Override
    public void run() {
      if (!allowRestore) {
        infoLines.add("***********************************\n");
        infoLines.add("********* Restores disabled *******\n");
        infoLines.add("***********************************\n");

        return;
      }

      try (final Restore restorer = new Restore(newRestoreFormat)) {
        if (!disableIndexer()) {
          infoLines.add("***********************************\n");
          infoLines.add("********* Unable to disable indexer\n");
          infoLines.add("***********************************\n");
        }

        infoLines.addLn("Started restore of data");

        final long startTime = System.currentTimeMillis();

        setStatus(statusRunning);

        restorer.getConfigProperties();

        infoLines.addLn("Restore file: " + getDataIn());
        info("Restore file: " + getDataIn());

        restorer.setFilename(getDataIn());

        restorer.open(true);

        restorer.doRestore(infoLines);

        externalSubs = restorer.getExternalSubs();
        aliasInfo = restorer.getAliasInfo();

        restorer.stats(infoLines);

        final long millis = System.currentTimeMillis() - startTime;
        final long seconds = millis / 1000;
        final long minutes = seconds / 60;

        infoLines.addLn("Elapsed time: " + minutes + ":" +
                                Restore.twoDigits(seconds - (minutes * 60)));

        infoLines.add("Restore complete" + "\n");
        setStatus(statusDone);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
        setStatus(statusFailed);
      } finally {
        infoLines.addLn("Restore completed - about to start indexer");

        try {
          if (!reindex()) {
            infoLines.addLn("***********************************");
            infoLines.addLn("********* Unable to reindex");
            infoLines.addLn("***********************************");
          }
        } catch (final Throwable t) {
          error(t);
          infoLines.exceptionMsg(t);
        }
      }
    }
  }

  private RestoreThread restore;

  private class DumpThread extends Thread {
    InfoLines infoLines = new InfoLines();
    boolean dumpAll;

    DumpThread(final boolean dumpAll) {
      super("DumpData");

      this.dumpAll = dumpAll;
    }

    @Override
    public void run() {
      try {
        final long startTime = System.currentTimeMillis();

        final Dump d = new Dump(infoLines, newDumpFormat);

        try {
          d.getConfigProperties();

          if (dumpAll) {
            infoLines.addLn("Started dump of data");

            if (newDumpFormat) {
              d.setDirPath(makeDirname());
              d.setLowercaseAccounts(lowercaseAccounts);
            } else {
              d.setFilename(makeFilename(getDataOutPrefix()));
              d.setAliasesFilename(
                      makeFilename("aliases-" + getDataOutPrefix()));
            }

            d.open(false);

            d.doDump();
          } else {
            infoLines
                    .addLn("Started search for external subscriptions");
            d.open(true);

            d.doExtSubs();
          }

          externalSubs = d.getExternalSubs();
          aliasInfo = d.getAliasInfo();
        } finally {
          d.close();

          d.stats(infoLines);

          infoLines.addLn("Elapsed time: " + elapsed(startTime));

          infoLines.addLn("Complete");
        }
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }
  
  private String elapsed(final long startTime) {
    final long millis = System.currentTimeMillis() - startTime;
    final long seconds = millis / 1000;
    final long minutes = seconds / 60;

    return minutes + ":" +
            Restore.twoDigits(
                    seconds - (minutes * 60));
  }

  private DumpThread dump;

  private class SubsThread extends Thread {
    InfoLines infoLines = new InfoLines();

    SubsThread() {
      super("Subs");
    }

    @Override
    public void run() {
      try {
        if (externalSubs.isEmpty()) {
          infoLines.addLn("No external subscriptions");

          return;
        }

        /* Number for which no action was required */
        int okCt = 0;

        /* Number not found */
        int notFoundCt = 0;

        /* Number for which no action was required */
        int notExternalCt = 0;

        /* Number resubscribed */
        int resubscribedCt = 0;

        /* Number of failures */
        int failedCt = 0;

        if (debug()) {
          debug("About to process " + externalSubs
                  .size() + " external subs");
        }

        int ct = 0;
        int accessErrorCt = 0;
        int errorCt = 0;

        resubscribe:
        for (final AliasInfo ai: externalSubs) {
          getSvci(ai.getOwner(), ai.getPublick());

          try {
            final CheckSubscriptionResult csr =
                    svci.getCollectionsHandler().checkSubscription(
                            ai.getPath());

            switch (csr) {
              case ok:
                okCt++;
                break;
              case notFound:
                notFoundCt++;
                break;
              case notExternal:
                notExternalCt++;
                break;
              case resubscribed:
                resubscribedCt++;
                break;
              case noSynchService:
                infoLines.addLn("Synch service is unavailable");
                info("Synch service is unavailable");
                break resubscribe;
              case failed:
                failedCt++;
                break;
            } // switch

            if ((csr != CheckSubscriptionResult.ok) &&
                    (csr != CheckSubscriptionResult.resubscribed)) {
              infoLines.addLn("Status: " + csr +
                                      " for " + ai.getPath() +
                                      " owner: " + ai.getOwner());
            }

            ct++;

            if ((ct % 100) == 0) {
              info("Checked " + ct + " collections");
            }
          } catch (final BedeworkAccessException ignored) {
            accessErrorCt++;

            if ((accessErrorCt % 100) == 0) {
              info("Had " + accessErrorCt + " access errors");
            }
          } catch (final Throwable t) {
            error(t);
            errorCt++;

            if ((errorCt % 100) == 0) {
              info("Had " + errorCt + " errors");
            }
          } finally {
            closeSvci();
          }
        } // resubscribe

        infoLines.addLn("Checked " + ct + " collections");
        infoLines.addLn("       errors: " + errorCt);
        infoLines.addLn("access errors: " + accessErrorCt);
        infoLines.addLn("           ok: " + okCt);
        infoLines.addLn("    not found: " + notFoundCt);
        infoLines.addLn("  notExternal: " + notExternalCt);
        infoLines.addLn(" resubscribed: " + resubscribedCt);
        infoLines.addLn("       failed: " + failedCt);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }

  private SubsThread subs;

  private class AliasesThread extends Thread {
    InfoLines infoLines = new InfoLines();

    AliasesThread() {
      super("Aliases");
    }

    @Override
    public void run() {
      try {
        if (aliasInfo.isEmpty()) {
          infoLines.addLn("No aliases");

          return;
        }

        /* Number for which no action was required */
        int okCt = 0;

        /* Number of public aliases */
        int publicCt = 0;

        /* Number not found - broken links */
        int notFoundCt = 0;

        /* Number for which no access */
        int noAccessCt = 0;

        /* Number fixed */
        int fixedCt = 0;

        /* Number of failures */
        int failedCt = 0;

        if (debug()) {
          debug("About to process " + aliasInfo.size() +
                        " aliases");
        }

        int ct = 0;
        int errorCt = 0;
        
        /* We need the owner field in the alias XML as the fix alias 
           process needs to be that user to determine the permissions 
           available to the sharee.

           The fix alias process runs in two phases. The first phase 
           becomes the alias owner (sharee) and determines the 
           permission that the sharee has on the target calendar. 
           
           The second phase becomes the target calendar owner (sharer) 
           and updates the invitation XML with the sharee information 
           and permissions. 

         */

        for (final String target: aliasInfo.keySet()) {
          final AliasEntry ae = aliasInfo.get(target);

          fix:
          for (final AliasInfo ai: ae.getAliases()) {
            BwCollection targetCol = null;
            final AccessType a = new AccessType();

            try {
              final CalSvcI svci = getSvci(ai.getOwner(), ai.getPublick());

              if (ai.getPublick()) {
                publicCt++;
                continue fix;
              }

              /* Try to fetch as sharee */
              try {
                targetCol = svci.getCollectionsHandler().get(target);
              } catch (final BedeworkAccessException ignored) {
                ai.setNoAccess(true);
                noAccessCt++;
                continue fix;
              }

              if (targetCol == null) {
                ai.setNoAccess(true);
                noAccessCt++;
                continue fix;
              }

              if (targetCol.getPublick()) {
                publicCt++;
                continue fix;
              }

              final boolean write = svci.checkAccess(targetCol,
                                                     PrivilegeDefs.privWrite,
                                                     true).getAccessAllowed();

              final boolean read = svci.checkAccess(targetCol,
                                                    PrivilegeDefs.privRead,
                                                    true).getAccessAllowed();

              if (!write && !read) {
                // No appropriate access
                noAccessCt++;
                continue fix;
              }

              if (write && !read) {
                // Incompatible with current sharing
                warn("Incompatible access for " + ai.getPath());
                continue fix;
              }

              if (write) {
                a.setReadWrite(true);
              } else {
                a.setRead(true);
              }

            } catch (final Throwable t) {
              error(t);
              errorCt++;

              if ((errorCt % 100) == 0) {
                info("Had " + errorCt + " errors");
              }
            } finally {
              closeSvci();
            }

            try {
              final CalSvcI svci = getSvci(targetCol.getOwnerHref(), false);

              final FixAliasResult far =
                      svci.getRestoreHandler()
                          .fixSharee(targetCol,
                                     ai.getOwner(),
                                     a);

              switch (far) {
                case ok:
                  okCt++;
                  break;
                case noAccess:
                  noAccessCt++;
                  break;
                case wrongAccess:
                  warn("Incompatible access for " + ai.getPath());
                  break;
                case notFound:
                  notFoundCt++;
                  break;
                case circular:
                  warn("Circular aliases for " + ai.getPath());
                  break;
                case broken:
                  notFoundCt++;
                  break;
                case reshared:
                  fixedCt++;
                  break;
                case failed:
                  failedCt++;
                  break;
              } // switch

              if ((far != FixAliasResult.ok) &&
                      (far != FixAliasResult.reshared)) {
                infoLines.addLn("Status: " + far + " for " + ai.getPath() +
                                        " owner: " + ai.getOwner());
              }

              ct++;

              if ((ct % 100) == 0) {
                info("Checked " + ct + " collections");
              }
            } catch (final BedeworkAccessException ignored) {
              noAccessCt++;

              if ((noAccessCt % 100) == 0) {
                info("Had " + noAccessCt + " access errors");
              }
            } catch (final Throwable t) {
              error(t);
              errorCt++;

              if ((errorCt % 100) == 0) {
                info("Had " + errorCt + " errors");
              }
            } finally {
              closeSvci();
            }
          } // fix
        }

        infoLines.addLn("Checked " + ct + " collections");
        infoLines.addLn("       errors: " + errorCt);
        infoLines.addLn("access errors: " + noAccessCt);
        infoLines.addLn("           ok: " + okCt);
        infoLines.addLn("       public: " + publicCt);
        infoLines.addLn("    not found: " + notFoundCt);
        infoLines.addLn("        fixed: " + fixedCt);
        infoLines.addLn("       failed: " + failedCt);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }

  private AliasesThread aliases;

  private class FixDataThread extends Thread {
    private final int hrefBatchSize = 1000;
    private int hrefBatchPos;
    
    final InfoLines infoLines = new InfoLines();
    
    boolean fixing;
    
    /* Number for which no action was required */
    int okCt = 0;

    /* Number changed */
    int changedCt = 0;

    /* Number already processed */
    int alreadyDoneCt = 0;

    /* Number without yale loc */
    int noLocCt = 0;

    /* Number where yale loc doesn't match */
    int missingLocCt = 0;

    /* Number of failures */
    int failedCt = 0;

    int ct = 0;
    int accessErrorCt = 0;
    int errorCt = 0;
    
    long startTime;

    final Map<String, BwLocation> locs = new HashMap<>();
    
    final Set<String> missingLocations = new TreeSet<>();

    FixDataThread(final int start) {
      super("FixData");

      hrefBatchPos = start;
    }

    @Override
    public void run() {
      CalSvcI svci = null;

      try {
        startTime = System.currentTimeMillis();
        
        fixing = true;
        
        okCt = 0;
        changedCt = 0;
        alreadyDoneCt = 0;
        noLocCt = 0;
        missingLocCt = 0;
        failedCt = 0;
        ct = 0;
        accessErrorCt = 0;
        errorCt = 0;
        locs.clear();
        
        if (debug()) {
          debug("About to get locations");
        }

        try {
          svci = getSvci("public-user", true);

          final Iterator<BwLocation> loci = svci.getDumpHandler()
                                                .getLocations();

          while (loci.hasNext()) {
            final BwLocation loc = loci.next();
            final String ids = loc.getSubField1();
            if (ids == null) {
              warn("No loc id for " + loc);
              continue;
            }
            
            /* The id may be a ; separated string of ids
             */

            String[] idsplit = {ids};
            
            if (ids.contains(";")) {
              idsplit = ids.split(";");
            }
            
            for (final String nxtId: idsplit) {
              if (nxtId == null) {
                continue;
              }
              
              final String id = nxtId.trim();
              if (id.isEmpty()) {
                continue;
              }
              
              if (locs.get(id) != null) {
                warn("Duplicate id " + id +
                             "for " + loc);
                continue;
              }
              locs.put(id, loc);
            }
          }

          infoLines.addLn("Found " + locs.size() + " locations");
        } finally {
          closeSvci();
        }
        
        doFix: while (true) {
          final List<String> hrefs = hrefBatch();

          if (Util.isEmpty(hrefs)) {
            break doFix;
          }
          
          for (final String href: hrefs) {
            try {
              svci = getSvci("public-user", true);

              final int nmpos = href.lastIndexOf('/');
              if (nmpos < 0) {
                warn("Invalid href " + href);
                continue; 
              }
              final EventInfo ei = 
                      svci.getEventsHandler().get(href.substring(0, nmpos), 
                                                  href.substring(nmpos + 1));
              if (ei == null) {
                warn("No event with href " + href);
                continue;
              }
              
              ct++;

              if (updateEvent(ei)) {
                changedCt++;
                
                try {
                  final EventInfo.UpdateResult ur =
                          svci.getEventsHandler().update(ei, true,
                                                         null,
                                                         false); // autocreate
                } catch (final Throwable t) {
                  errorCt++;
                  warn("Error updating event " + href + 
                               " " + t.getLocalizedMessage());
                }
              }
            } finally {
              closeSvci();
            }
          }
          
          if ((ct % 100) == 0) {
            info("Processed " + ct + " at " + hrefBatchPos);
          }
        }
        info("Processed " + ct + " at " + hrefBatchPos);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      } finally {
        infoLines.addAll(fixDataStatus());
        fixing = false;
        locs.clear();
        
        if (svci != null) {
          try {
            closeSvci();
          } catch (final Throwable t) {
            error(t);
          }
        }
      }
    }
    
    private List<String> hrefBatch() {
      final List<String> hrefs = new ArrayList<>(hrefBatchSize);
      
      try {
        svci = getSvci("public-user", true);
        
        final Iterator<String> hrefi =
                svci.getDumpHandler().getEventHrefs(hrefBatchPos);
        
        for (int i = 0; i < hrefBatchSize; i++) {
          hrefBatchPos++;
          if (!hrefi.hasNext()) {
            break;
          }
          
          hrefs.add(hrefi.next());
        }

        return hrefs;
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
        return null;
      } finally {
        if (svci != null) {
          try {
            closeSvci();
          } catch (final Throwable t) {
            error(t);
          }
        }
      }
    }
    
    private boolean updateEvent(final EventInfo ei) {
      final BwEvent ev = ei.getEvent();
      boolean changed = false;
          
      updateEv: {
        /* Yale events are referenced by an X-prop - X-YALE-LOCATION
           */

        final BwXproperty yaleLoc = ev.findXproperty("X-YALE-LOCATION");

        if (yaleLoc == null) {
          noLocCt++;
          break updateEv;
        }

        final String yaleLocId = yaleLoc.getValue();

        if (yaleLocId == null) {
          noLocCt++;
          break updateEv;
        }

        if (yaleLocId.startsWith("DONE-")) {
          alreadyDoneCt++;
          break updateEv;
        }

        final BwLocation loc = locs.get(yaleLocId);

        if (loc == null) {
          missingLocCt++;
          missingLocations.add(yaleLocId);
          break updateEv;
        }

        if (loc.equals(ev.getLocation())) {
          break updateEv;
        }
        
        ev.setLocation(loc);
        yaleLoc.setValue("DONE-" + yaleLocId);
        
        changed = true;
      } // updateEv
      
      if (!Util.isEmpty(ei.getOverrides())) {
        for (final EventInfo ovei: ei.getOverrides()) {
          if (updateEvent(ovei)) {
            changed = true;
          }
        }
      }
      
      // Force update
      ei.clearChangeset();
      
      return changed;
    }
  }
  
  private FixDataThread fixer;

  private final static String nm = "dumprestore";

  /**
   */
  public BwDumpRestore() {
    super(getServiceName(nm), confDirName, nm);
  }

  /**
   * @param name of the service
   * @return object name value for the mbean with this name
   */
  @SuppressWarnings("WeakerAccess")
  public static String getServiceName(final String name) {
    return "org.bedework.bwengine:service=" + name;
  }

  /* ========================================================================
   * Attributes
   * ======================================================================== */

  @Override
  public void setAccount(final String val) {
    getConfig().setAccount(val);
  }

  @Override
  public String getAccount() {
    return getConfig().getAccount();
  }

  @Override
  public void setDataIn(final String val) {
    getConfig().setDataIn(val);
  }

  @Override
  public String getDataIn() {
    return getConfig().getDataIn();
  }

  @Override
  public void setDataOut(final String val) {
    getConfig().setDataOut(val);
  }

  @Override
  public String getDataOut() {
    return getConfig().getDataOut();
  }

  @Override
  public void setDataOutPrefix(final String val) {
    getConfig().setDataOutPrefix(val);
  }

  @Override
  public String getDataOutPrefix() {
    return getConfig().getDataOutPrefix();
  }

  @Override
  public DumpRestoreProperties cloneIt() {
    return getConfig().cloneIt();
  }

  @Override
  public String loadConfig() {
    return loadConfig(DumpRestorePropertiesImpl.class);
  }

  @Override
  public void setAllowRestore(final boolean val) {
    allowRestore = val;
  }

  @Override
  public boolean getAllowRestore() {
    return allowRestore;
  }

  @Override
  public void setFixAliases(final boolean val) {
    fixAliases = val;
  }

  @Override
  public boolean getFixAliases() {
    return fixAliases;
  }

  @Override
  public void setLowercaseAccounts(final boolean val) {
    lowercaseAccounts = val;
  }

  @Override
  public boolean getLowercaseAccounts() {
    return lowercaseAccounts;
  }

  @Override
  public void setNewRestoreFormat(final boolean val) {
    newRestoreFormat = val;
  }

  @Override
  public boolean getNewRestoreFormat() {
    return newRestoreFormat;
  }

  @Override
  public void setNewDumpFormat(final boolean val) {
    newDumpFormat = val;
  }

  @Override
  public boolean getNewDumpFormat() {
    return newDumpFormat;
  }

  @Override
  public synchronized String restoreData() {
    try {
      setStatus(statusStopped);
      restore = new RestoreThread();

      restore.start();

      return "OK";
    } catch (final Throwable t) {
      setStatus(statusFailed);
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public List<String> restoreStatus() {
    if (restore == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Restore has not been started");

      return infoLines;
    }

    return restore.infoLines;
  }

  @Override
  public String fixData(final int start) {
    try {
      setStatus(statusStopped);
      fixer = new FixDataThread(start);

      fixer.start();

      return "OK";
    } catch (final Throwable t) {
      setStatus(statusFailed);
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  public List<String> fixDataStatus() {
    if (fixer == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Fix data has not been started");

      return infoLines;
    }

    if (!fixer.fixing) {
      return fixer.infoLines;
    }

    final InfoLines infoLines = new InfoLines();

    infoLines.addLn("      Checked: " + fixer.ct + " events");
    infoLines.addLn("       errors: " + fixer.errorCt);
    infoLines.addLn("access errors: " + fixer.accessErrorCt);
    infoLines.addLn("           ok: " + fixer.okCt);
    infoLines.addLn(" already done: " + fixer.alreadyDoneCt);
    infoLines.addLn(" missing locs: " + fixer.missingLocCt);
    infoLines.addLn("       no loc: " + fixer.noLocCt);
    infoLines.addLn("      changed: " + fixer.changedCt);
    infoLines.addLn("       failed: " + fixer.failedCt);
    infoLines.addLn("Elapsed time: " + elapsed(fixer.startTime));

    infoLines.addLn("Missing locations: ");
    for (final String ml: fixer.missingLocations) {
      infoLines.addLn("    " + ml);
    }

    return infoLines;
  }

  @Override
  public String loadAliasInfo(final String path) {
    try {
      final FromXml fxml = new FromXml();

      final Document doc = fxml.parseXml(new FileInputStream(path));

      final Element el = doc.getDocumentElement();

      if (!el.getTagName().equals(Defs.aliasInfoTag)) {
        return "Not an alias-info dump file - incorrect root element " +
                el;
      }

      externalSubs = new ArrayList<>();
      aliasInfo = new HashMap<>();

      for (final Element child: XmlUtil.getElementsArray(el)) {
        switch (child.getTagName()) {
          case Defs.majorVersionTag, Defs.minorVersionTag,
               Defs.versionTag, Defs.dumpDateTag -> {
            continue;
          }
          case Defs.extsubsTag -> {
            for (final Element extSubEl: XmlUtil.getElementsArray(
                    child)) {
              externalSubs.add(fxml.fromXml(extSubEl,
                                            AliasInfo.class,
                                            null));
            }

            continue;
          }
          case Defs.aliasesTag -> {
            for (final Element aliasEl: XmlUtil.getElementsArray(
                    child)) {
              final AliasEntry ae = fxml.fromXml(aliasEl,
                                                 AliasEntry.class,
                                                 null);
              aliasInfo.put(ae.getTargetPath(), ae);
            }

            continue;
          }
        }

        return "Not an alias-info dump file: unexpected element " + child;
      }
      return "Ok";
    } catch (final Throwable t) {
      error(t);
      return t.getMessage();
    }
  }

  @Override
  public String fetchExternalSubs() {
    try {
      dump = new DumpThread(false);

      dump.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public String checkExternalSubs() {
    if (externalSubs == null) {
      return "No external subscriptions - do dump or restore first";
    }

    try {
      subs = new SubsThread();

      subs.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public List<String> checkSubsStatus() {
    if (subs == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Subscriptions check has not been started");

      return infoLines;
    }

    return subs.infoLines;
  }

  @Override
  public String checkAliases() {
    if (aliasInfo.isEmpty()) {
      return "No alias info - do dump or restore first";
    }

    try {
      aliases = new AliasesThread();

      aliases.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public List<String> checkAliasStatus() {
    if (aliases == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Aliases check has not been started");

      return infoLines;
    }

    return aliases.infoLines;
  }

  @Override
  public String dumpData() {
    try {
      dump = new DumpThread(true);

      dump.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public synchronized List<String> dumpStatus() {
    if (dump == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Dump has not been started");

      return infoLines;
    }

    return dump.infoLines;
  }

  @Override
  public String deleteUser(final String account) {
    try {
      final CalSvcI svci = getSvci(getConfig().getAccount(), true);

      final var pr = svci.getUsersHandler().getUser(account);

      if (pr == null) {
        return "No principal for " + account;
      }

      svci.getUsersHandler().remove(pr);

      return "ok";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    } finally {
      try {
        closeSvci();
      } catch (final Throwable t) {
        error(t);
      }
    }
  }

  @Override
  public String restoreUser(final String account,
                            final boolean merge,
                            final boolean dryRun) {
    final InfoLines infoLines = new InfoLines();
    
    try (final Restore restorer = new Restore(newRestoreFormat)) {
      restorer.getConfigProperties();
      restorer.setFilename(getDataIn());

      infoLines.addLn("Restore user from: " + getDataIn());
      info("Restore user from : " + getDataIn());
      
      restorer.open(false);

      restorer.restoreUser(account, merge, dryRun, infoLines);

      restorer.stats(infoLines);
      
      return infoLines.toString();
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage() + 
              "|nInfo: " + infoLines;
    }
  }

  @Override
  public String restorePublic(final boolean merge,
                              final boolean dryRun) {
    final InfoLines infoLines = new InfoLines();

    try (final Restore restorer = new Restore(newRestoreFormat)) {
      restorer.getConfigProperties();
      restorer.setFilename(getDataIn());

      infoLines.addLn("Restore public data from: " + getDataIn());
      info("Restore public data from : " + getDataIn());

      restorer.open(false);

      restorer.restorePublic(merge, dryRun, infoLines);

      restorer.stats(infoLines);

      return infoLines.toString();
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage() +
              "|nInfo: " + infoLines;
    }
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  private BwIndexCtlMBean indexer;

  private boolean disableIndexer() {
    try {
      if (indexer == null) {
        indexer = (BwIndexCtlMBean)MBeanUtil.getMBean(BwIndexCtlMBean.class,
                                                      "org.bedework.bwengine:service=indexing");
      }

      indexer.setDiscardMessages(true);
      return true;
    } catch (final Throwable t) {
      error(t);
      return false;
    }
  }

  private boolean reindex() {
    try {
      if (indexer == null) {
        return false;
      }
      indexer.rebuildIndex();
      indexer.setDiscardMessages(false);
      return true;
    } catch (final Throwable t) {
      error(t);
      return false;
    }
  }

  private String makeDirname() {
    return Util.buildPath(true, getDataOut(), "/", 
                          DateTimeUtil.isoDateTime());
  }

  private String makeFilename(final String val) {
    final StringBuilder fname = new StringBuilder(getDataOut());
    if (!getDataOut().endsWith("/")) {
      fname.append("/");
    }

    fname.append(val);

    /* append "yyyyMMddTHHmmss" */
    fname.append(DateTimeUtil.isoDateTime());
    fname.append(".xml");

    return fname.toString();
  }

  /** Get an svci object and return it. Also embed it in this object.
   *
   * @return svci object
   */
  private CalSvcI getSvci(final String owner, final boolean publick) {
    if ((svci != null) && svci.isOpen()) {
      return svci;
    }

    boolean publicAdmin = false;

    if ((curSvciOwner == null) || !curSvciOwner.equals(owner)) {
      svci = null;

      curSvciOwner = owner;
      publicAdmin = publick;
    }

    if (svci == null) {
      final CalSvcIPars pars = CalSvcIPars.getIndexerPars(curSvciOwner,
                                                          publicAdmin);
      //CalSvcIPars pars = CalSvcIPars.getServicePars(curSvciOwner,
      //                                              publicAdmin,   // publicAdmin
      //                                              true);   // Allow super user
      svci = new CalSvcFactoryDefault().getSvc(pars);
    }

    svci.open();
    svci.beginTransaction();

    return svci;
  }

  private void closeSvci() {
    if ((svci == null) || !svci.isOpen()) {
      return;
    }

    try {
      svci.endTransaction();
    } catch (final Throwable t) {
      error(t);
    }

    try {
      svci.close();
    } catch (final Throwable ignored) {
    }
  }
}
