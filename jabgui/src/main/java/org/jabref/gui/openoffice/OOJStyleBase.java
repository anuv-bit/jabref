package org.jabref.gui.openoffice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jabref.gui.DialogService;
import org.jabref.logic.citationstyle.CitationStyle;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.openoffice.OpenOfficePreferences;
import org.jabref.logic.openoffice.action.EditInsert;
import org.jabref.logic.openoffice.action.EditMerge;
import org.jabref.logic.openoffice.action.EditSeparate;
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
import org.jabref.model.openoffice.uno.UnoCrossRef;
import org.jabref.model.openoffice.uno.UnoUndo;
import org.jabref.model.openoffice.util.OOResult;

import com.sun.star.beans.IllegalTypeException;
import com.sun.star.beans.NotRemoveableException;
import com.sun.star.beans.PropertyVetoException;
import com.sun.star.comp.helper.BootstrapException;
import com.sun.star.lang.DisposedException;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextDocument;

public class OOJStyleBase extends OOBibBase {

    public OOJStyleBase(Path loPath, DialogService dialogService, OpenOfficePreferences openOfficePreferences)
            throws BootstrapException, CreationException, IOException, InterruptedException {
        super(loPath, dialogService, openOfficePreferences);
    }

    /// Creates a citation group from `entries` at the cursor.
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

            // Handle insertion of JStyle citations
            if (style instanceof JStyle jstyle) {
                insertJStyleCitation(entries, doc, citationType, jstyle, frontend, cursor, bibDatabaseContext, syncOptions, pageInfo, fcursor);
            }
        } catch (NoDocumentException ex) {
            OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
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

    /// Helper method for guiActionInsertEntry
    /// Throws PropertyVetoException, WrappedTargetException, IllegalTypeException, NotRemoveableException, CreationException, NoDocumentException
    /// Exceptions caught by guiActionInsertEntry
    ///
    /// @param entries            The entries to cite.
    /// @param citationType       Indicates whether it is an in-text citation, a citation in parentheses or an invisible citation.
    /// @param jStyle             Indicates citation formating in JStyle
    /// @param bibDatabaseContext The database the entries belong to (all of them). Used when creating the citation mark.
    /// @param syncOptions        Indicates whether in-text citations should be refreshed in the document. Optional.empty() indicates no refresh. Otherwise, provides options for refreshing the reference list.
    /// @param pageInfo           A single page-info for these entries. Attributed to the last entry.
    public void insertJStyleCitation(List<BibEntry> entries, XTextDocument doc, CitationType citationType, JStyle jStyle, OOResult<OOFrontend, OOError> frontend,
                                     OOResult<XTextCursor, OOError> cursor, BibDatabaseContext bibDatabaseContext, Optional<Update.SyncOptions> syncOptions,
                                     String pageInfo, OOResult<FunctionalTextViewCursor, OOError> fcursor)
            throws PropertyVetoException, WrappedTargetException, IllegalTypeException, NotRemoveableException, CreationException, NoDocumentException {
        EditInsert.insertCitationGroup(doc,
                frontend.get(),
                cursor.get(),
                entries,
                bibDatabaseContext.getDatabase(),
                jStyle,
                citationType,
                pageInfo);

        if (syncOptions.isPresent()) {
            Update.resyncDocument(doc, jStyle, fcursor.get(), syncOptions.get());
        }
    }

    /// GUI action "Merge citations"
    public void guiActionMergeCitationGroups(List<BibDatabase> databases, OOStyle style) {
        final String errorTitle = Localization.lang("Problem combining cite markers");

        if (style instanceof JStyle jStyle) {
            OOResult<XTextDocument, OOError> odoc = getXTextDocument();
            if (testDialog(errorTitle,
                    odoc.asVoidResult(),
                    styleIsRequired(jStyle),
                    databaseIsRequired(databases, OOError::noDataBaseIsOpen))) {
                return;
            }
            XTextDocument doc = odoc.get();

            OOResult<FunctionalTextViewCursor, OOError> fcursor = getFunctionalTextViewCursor(doc, errorTitle);

            if (testDialog(errorTitle,
                    fcursor.asVoidResult(),
                    checkStylesExistInTheDocument(jStyle, doc),
                    checkIfOpenOfficeIsRecordingChanges(doc))) {
                return;
            }

            try {
                UnoUndo.enterUndoContext(doc, "Merge citations");

                OOFrontend frontend = new OOFrontend(doc);
                boolean madeModifications = EditMerge.mergeCitationGroups(doc, frontend, jStyle);
                if (madeModifications) {
                    UnoCrossRef.refresh(doc);
                    Update.SyncOptions syncOptions = new Update.SyncOptions(databases);
                    Update.resyncDocument(doc, jStyle, fcursor.get(), syncOptions);
                }
            } catch (NoDocumentException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (DisposedException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (CreationException
                     | IllegalTypeException
                     | NotRemoveableException
                     | PropertyVetoException
                     | WrappedTargetException
                     | com.sun.star.lang.IllegalArgumentException ex) {
                getLOGGER().warn(errorTitle, ex);
                OOError.fromMisc(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } finally {
                UnoUndo.leaveUndoContext(doc);
                fcursor.get().restore(doc);
            }
        }
    } // MergeCitationGroups

    /// GUI action "Separate citations".
    ///
    /// Do the opposite of MergeCitationGroups. Combined markers are split, with a space inserted between.
    public void guiActionSeparateCitations(List<BibDatabase> databases, OOStyle style) {
        final String errorTitle = Localization.lang("Problem during separating cite markers");

        if (style instanceof JStyle jStyle) {
            OOResult<XTextDocument, OOError> odoc = getXTextDocument();
            if (testDialog(errorTitle,
                    odoc.asVoidResult(),
                    styleIsRequired(jStyle),
                    databaseIsRequired(databases, OOError::noDataBaseIsOpen))) {
                return;
            }

            XTextDocument doc = odoc.get();
            OOResult<FunctionalTextViewCursor, OOError> fcursor = getFunctionalTextViewCursor(doc, errorTitle);

            if (testDialog(errorTitle,
                    fcursor.asVoidResult(),
                    checkStylesExistInTheDocument(jStyle, doc),
                    checkIfOpenOfficeIsRecordingChanges(doc))) {
                return;
            }

            try {
                UnoUndo.enterUndoContext(doc, "Separate citations");

                OOFrontend frontend = new OOFrontend(doc);
                boolean madeModifications = EditSeparate.separateCitations(doc, frontend, databases, jStyle);
                if (madeModifications) {
                    UnoCrossRef.refresh(doc);
                    Update.SyncOptions syncOptions = new Update.SyncOptions(databases);
                    Update.resyncDocument(doc, jStyle, fcursor.get(), syncOptions);
                }
            } catch (NoDocumentException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (DisposedException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (CreationException
                     | IllegalTypeException
                     | NotRemoveableException
                     | PropertyVetoException
                     | WrappedTargetException
                     | com.sun.star.lang.IllegalArgumentException ex) {
                getLOGGER().warn(errorTitle, ex);
                OOError.fromMisc(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } finally {
                UnoUndo.leaveUndoContext(doc);
                fcursor.get().restore(doc);
            }
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
        OOResult<OOFrontend, OOError> frontend = getFrontend(doc);

        if (!performPreUpdateChecks(errorTitle, odoc, style, getFrontend(doc), fcursor, doc)) {
            return;
        }

        if (style instanceof JStyle jStyle) {
            try {

                updateJStyleBibliography(databases, jStyle, doc, frontend.get(), fcursor, errorTitle);
            } catch (NoDocumentException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (DisposedException ex) {
                OOError.from(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            } catch (CreationException
                     | WrappedTargetException
                     | com.sun.star.lang.IllegalArgumentException ex) {
                getLOGGER().warn("Could not update JStyle bibliography", ex);
                OOError.fromMisc(ex).setTitle(errorTitle).showErrorDialog(super.getDialogService());
            }
        }
    }

    /// Helper method for guiActionUpdateDocument, refreshes a JStyle bibliography.
    ///
    /// @param databases        Must have at least one.
    /// @param jStyle           Indicates citation formating in JStyle.
    /// @param doc              Text document.
    /// @param frontend,fcursor Used to synchronize document.
    /// @param errorTitle       Error message for user.
    private void updateJStyleBibliography(List<BibDatabase> databases, JStyle jStyle, XTextDocument doc, OOFrontend frontend,
                                          OOResult<FunctionalTextViewCursor, OOError> fcursor, String errorTitle)
            throws CreationException, NoDocumentException, WrappedTargetException {
        List<String> unresolvedKeys;
        try {
            UnoUndo.enterUndoContext(doc, "Refresh bibliography");

            Update.SyncOptions syncOptions = new Update.SyncOptions(databases);
            syncOptions
                    .setUpdateBibliography(true)
                    .setAlwaysAddCitedOnPages(super.getOpenOfficePreferences().getAlwaysAddCitedOnPages());

            unresolvedKeys = Update.synchronizeDocument(doc, frontend, jStyle, fcursor.get(), syncOptions);
        } finally {
            UnoUndo.leaveUndoContext(doc);
            fcursor.get().restore(doc);
        }

        if (!unresolvedKeys.isEmpty()) {
            String msg = Localization.lang(
                    "Your OpenOffice/LibreOffice document references the citation key '%0',"
                            + " which could not be found in your current library.",
                    unresolvedKeys.getFirst());
            super.getDialogService().showErrorDialogAndWait(errorTitle, msg);
        }
    }
}
