package org.jabref.gui.openoffice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jabref.gui.DialogService;
import org.jabref.logic.citationstyle.CitationStyle;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.openoffice.OpenOfficePreferences;
import org.jabref.logic.openoffice.action.Update;
import org.jabref.logic.openoffice.frontend.OOFrontend;
import org.jabref.logic.openoffice.style.JStyle;
import org.jabref.logic.openoffice.style.OOStyle;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.openoffice.rangesort.FunctionalTextViewCursor;
import org.jabref.model.openoffice.style.CitationType;
import org.jabref.model.openoffice.uno.CreationException;
import org.jabref.model.openoffice.uno.NoDocumentException;
import org.jabref.model.openoffice.uno.UnoUndo;
import org.jabref.model.openoffice.util.OOResult;

import com.airhacks.afterburner.injection.Injector;
import com.sun.star.beans.IllegalTypeException;
import com.sun.star.beans.NotRemoveableException;
import com.sun.star.beans.PropertyVetoException;
import com.sun.star.comp.helper.BootstrapException;
import com.sun.star.lang.DisposedException;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextDocument;

/// Subclass with JStyle specific methods from OOBibBase
public class OOCSLBase extends OOBibBase {

    public OOCSLBase(Path loPath, DialogService dialogService, OpenOfficePreferences openOfficePreferences)
            throws BootstrapException, CreationException, IOException, InterruptedException {
        super(loPath, dialogService, openOfficePreferences);
    }

    // Creates a citation group from `entries` at the cursor.

    ///
    /// Uses LO undo context "Insert citation".
    ///
    /// Note: Undo does not remove or reestablish custom properties.
    ///
    /// Consistency: for each entry in `entries`: looking it up in `syncOptions.get().databases` (if present) should yield `database`.
    ///
    /// @param entries            The entries to cite.
    /// @param bibDatabaseContext The database the entries belong to (all of them). Used when creating the citation mark.
    /// @param style              The bibliography style we are using.
    /// @param citationType       Indicates whether it is an in-text citation, a citation in parenthesis or an invisible citation.
    /// @param pageInfo           A single page-info for these entries. Attributed to the last entry.
    /// @param syncOptions        Indicates whether in-text citations should be refreshed in the document. Optional.empty() indicates no refresh. Otherwise, provides options for refreshing the reference list.
    public void guiActionInsertEntry(List<BibEntry> entries,
                                     BibDatabaseContext bibDatabaseContext,
                                     BibEntryTypesManager bibEntryTypesManager,
                                     OOStyle style,
                                     CitationType citationType,
                                     String pageInfo,
                                     Optional<Update.SyncOptions> syncOptions) {

        final String errorTitle = "Could not insert citation";

        OOResult<XTextDocument, OOError> odoc = getXTextDocument();

        XTextDocument doc = odoc.get();

        OOResult<OOFrontend, OOError> frontend = getFrontend(doc);

        OOResult<XTextCursor, OOError> cursor = getUserCursorForTextInsertion(doc, errorTitle);

        if (!performPreInsertionChecks(entries, errorTitle, odoc, style, frontend, cursor, doc)) {
            return;
        }

        /*
         * For sync we need a FunctionalTextViewCursor and an open database.
         */
        OOResult<FunctionalTextViewCursor, OOError> fcursor = null;
        if (syncOptions.isPresent()) {
            fcursor = getFunctionalTextViewCursor(doc, errorTitle);
            syncOptions.map(e -> e.setAlwaysAddCitedOnPages(super.getOpenOfficePreferences().getAlwaysAddCitedOnPages()));
            if (testDialog(errorTitle, fcursor.asVoidResult()) || testDialog(databaseIsRequired(syncOptions.get().databases,
                    OOError::noDataBaseIsOpenForSyncingAfterCitation))) {
                return;
            }
        }

        try {

            UnoUndo.enterUndoContext(doc, "Insert citation");

            // Handle insertion of CSL Style citations
            if (style instanceof CitationStyle citationStyle) {
                insertCSLCitation(entries, doc, citationType, citationStyle, bibDatabaseContext, bibEntryTypesManager, cursor, syncOptions);
            }
        } catch (DisposedException ex) {
            OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
        } catch (CreationException
                 | WrappedTargetException
                 | PropertyVetoException
                 | IllegalTypeException
                 | NotRemoveableException ex) {
            getLOGGER().warn("Could not insert entry", ex);
            OOError.fromMisc(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
        } catch (com.sun.star.uno.Exception e) {
            getLOGGER().error("Could not insert entry", e);
            OOError.fromMisc(e).setTitle(errorTitle).showErrorDialog(super.getDialogService());
        } finally {
            UnoUndo.leaveUndoContext(doc);
        }
    }

    /// Helper method for guiActionInsertEntry. Handles CSL citation insertion
    /// Throws CreationException, com.sun.star.uno.Exception
    /// Caught by guiActionInsertEntry
    ///
    /// @param entries            The entries to cite.
    /// @param bibDatabaseContext The database the entries belong to (all of them). Used when creating the citation mark.
    /// @param citationType       Indicates whether it is an in-text citation, a citation in parenthesis or an invisible citation.
    /// @param citationStyle      Indicates style, name and path of citation
    /// @param syncOptions        Indicates whether in-text citations should be refreshed in the document. Optional.empty() indicates no refresh. Otherwise, provides options for refreshing the reference list.
    public void insertCSLCitation(List<BibEntry> entries, XTextDocument doc, CitationType citationType, CitationStyle citationStyle,
                                  BibDatabaseContext bibDatabaseContext, BibEntryTypesManager bibEntryTypesManager, OOResult<XTextCursor, OOError> cursor,
                                  Optional<Update.SyncOptions> syncOptions) throws CreationException, com.sun.star.uno.Exception {
        try {
            // Lock document controllers - disable refresh during the process (avoids document flicker during writing)
            // MUST always be paired with an unlockControllers() call
            doc.lockControllers();

            if (citationType == CitationType.AUTHORYEAR_PAR) {
                // "Cite" button
                super.getCslCitationOOAdapter().insertCitation(cursor.get(), citationStyle, entries, bibDatabaseContext, bibEntryTypesManager);
            } else if (citationType == CitationType.AUTHORYEAR_INTEXT) {
                // "Cite in-text" button
                super.getCslCitationOOAdapter().insertInTextCitation(cursor.get(), citationStyle, entries, bibDatabaseContext, bibEntryTypesManager);
            } else if (citationType == CitationType.INVISIBLE_CIT) {
                // "Insert empty citation"
                super.getCslCitationOOAdapter().insertEmptyCitation(cursor.get(), citationStyle, entries);
            }

            // If "Automatically sync bibliography when inserting citations" is enabled
            if (citationStyle.hasBibliography()) {
                syncOptions.ifPresent(options -> guiActionUpdateDocument(options.databases, citationStyle));
            }
        } finally {
            // Release controller lock
            doc.unlockControllers();
        }
    }

    /// GUI action, refreshes citation markers and bibliography.
    ///
    /// @param databases Must have at least one.
    /// @param style     Style.
    public void guiActionUpdateDocument(List<BibDatabase> databases, OOStyle style) {
        final String errorTitle = Localization.lang("Unable to synchronize bibliography");

        OOResult<XTextDocument, OOError> odoc = getXTextDocument();
        XTextDocument doc = odoc.get();
        OOResult<FunctionalTextViewCursor, OOError> fcursor = getFunctionalTextViewCursor(doc, errorTitle);

        if (!performPreUpdateChecks(errorTitle, odoc, style, getFrontend(doc), fcursor, doc)) {
            return;
        }

        if (style instanceof CitationStyle citationStyle) {
            if (!citationStyle.hasBibliography()) {
                return;
            }
            try {

                updateCSLBibliography(databases, citationStyle, doc, fcursor);
            } catch (CreationException | com.sun.star.lang.IllegalArgumentException ex) {
                getLOGGER().error("Could not update CSL bibliography", ex);
                OOError.fromMisc(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            }
        }
    }

    /// Helper method for guiActionUpdateDocument, refreshes a CSL bibliography.
    ///
    /// @param databases     Must have at least one.
    /// @param citationStyle Citation style to update bibliography with.
    /// @param doc           Text document.
    /// @param fcursor       Used to synchronize document.
    private void updateCSLBibliography(List<BibDatabase> databases, CitationStyle citationStyle, XTextDocument doc,
                                       OOResult<FunctionalTextViewCursor, OOError> fcursor)
            throws CreationException {
        try {
            UnoUndo.enterUndoContext(doc, "Create CSL bibliography");

            // Collect only cited entries from all databases
            List<BibEntry> citedEntries = databases.stream()
                                                   .flatMap(database -> database.getEntries().stream())
                                                   .filter(super.getCslCitationOOAdapter()::isCitedEntry)
                                                   .collect(Collectors.toCollection(ArrayList::new));

            // If no entries are cited, show a message and return
            if (citedEntries.isEmpty()) {
                super.getDialogService().showInformationDialogAndWait(
                        Localization.lang("Bibliography"),
                        Localization.lang("No cited entries found in the document.")
                );
                return;
            }

            // A separate database and database context
            BibDatabase bibDatabase = new BibDatabase(citedEntries);
            BibDatabaseContext bibDatabaseContext = new BibDatabaseContext(bibDatabase);

            // Lock document controllers - disable refresh during the process (avoids document flicker during writing)
            // MUST always be paired with an unlockControllers() call
            doc.lockControllers();

            super.getCslUpdateBibliography().rebuildCSLBibliography(doc, super.getCslCitationOOAdapter(), citedEntries, citationStyle, bibDatabaseContext, Injector.instantiateModelOrService(BibEntryTypesManager.class));
        } catch (NoDocumentException | com.sun.star.uno.Exception e) {
            super.getDialogService().notify(Localization.lang("No document found or LibreOffice insertion failure"));
            getLOGGER().error("Could not update CSL bibliography", e);
        } finally {
            doc.unlockControllers();
            UnoUndo.leaveUndoContext(doc);
            fcursor.get().restore(doc);
        }
    }
}
