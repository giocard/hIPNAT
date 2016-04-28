/*
 * #%L
 * hIPNAT plugins for Fiji distribution of ImageJ
 * %%
 * Copyright (C) 2016 Tiago Ferreira
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ipnat;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Vector;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.DialogListener;
import ij.gui.GUI;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.util.Tools;
import ij3d.Image3DUniverse;
import sholl.gui.EnhancedGenericDialog;
import tracing.Path;
import tracing.PathAndFillManager;
import tracing.SimpleNeuriteTracer;

// TODO: implement other rending options: tagged skeleton, 3D viewer, ClearVolume
public class ImportSWC extends SimpleNeuriteTracer implements PlugIn, DialogListener {

	/* Default options for swc import */
	final double DEFAULT_OFFSET = 0d;
	final double DEFAULT_SCALE = 1d;
	final float DEFAULT_SPACING = 1f;
	final String DEF_VOXEL_UNIT = "\u00B5m";
	final double DEF_VOXEL_WIDTH = 1d;
	final double DEF_VOXEL_HEIGHT = 1d;
	final double DEF_VOXEL_DEPTH = 1d;

	private final String PREFS_KEY = "tracing.SWCImportOptionsDialog.";
	private EnhancedGenericDialog settingsDialog;
	private final String[] RENDING_OPTIONS = new String[] { "3D viewer (color)", "3D viewer (monochrome)",
			"Untagged skeleton" };
	private final int COLOR_3DVIEWER = 0;
	private final int GRAY_3DVIEWER = 1;
	private final int UNTAGGED_SKEL = 2;
	private int rendingChoice = COLOR_3DVIEWER;

	private double xOffset, yOffset, zOffset;
	private double xScale, yScale, zScale;
	private boolean applyOffset, applyScale, ignoreCalibration;
	private double voxelWidth, voxelHeight, voxelDepth;
	private String voxelUnit;
	private File chosenFile;
	private boolean guessOffsets = true;

	/**
	 * Calls {@link fiji.Debug#runPlugIn(String, String, boolean)
	 * fiji.Debug.runPlugIn()} so that the plugin can be debugged from an IDE
	 */
	public static void main(final String[] args) {
		//Debug.runPlugIn("ipnat.ImportSWC", "", false);
		ImageJ ij = IJ.getInstance();
		if (ij == null || (ij != null && !ij.isShowing()))
			ij = new ImageJ();
		IJ.runPlugIn("ipnat.ImportSWC", "");
	}

	@Override
	public void run(final String arg) {

		if (chosenFile==null) {
			final OpenDialog od = new OpenDialog("Open .swc file...", null, null);
			final String directory = od.getDirectory();
			final String fileName = od.getFileName();
			if (fileName == null) // User pressed "Cancel"
				return;
			chosenFile = new File(directory, fileName);
		}

		if (!chosenFile.exists()) {
			IJ.error("The file '" + chosenFile.getAbsolutePath() + "' is not available");
			return;
		}

		// Allow any type of paths in PathAndFillManager by exaggerating its
		// dimensions. We'll set x,y,z spacing to 1 with no spatial calibration
		pathAndFillManager = new PathAndFillManager(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1f, 1f, 1f,
				null);

		// Retrieve import options from user and load paths from file
		boolean successfullImport = getImportSettingsFromUser();
		if (!successfullImport)
			return;
		saveDialogSettings();
		successfullImport = pathAndFillManager.importSWC(
				chosenFile.getAbsolutePath(),
				ignoreCalibration,
				applyOffset ? xOffset : DEFAULT_OFFSET,
				applyOffset ? yOffset : DEFAULT_OFFSET,
				applyOffset ? zOffset : DEFAULT_OFFSET,
				applyScale ? xScale : DEFAULT_SCALE,
				applyScale ? yScale : DEFAULT_SCALE,
				applyScale ? zScale : DEFAULT_SCALE,
				true);
		if (!successfullImport || pathAndFillManager == null || pathAndFillManager.size() == 0) {
			IJ.error("Unable to load paths from swc file.");
			return;
		}

		// Calculate smallest dimensions of stack holding the rendering of paths
		// and suggest users with suitable offsets in case input was not suitable
		int cropped_canvas_x = 1;
		int cropped_canvas_y = 1;
		int cropped_canvas_z = 1;
		int x_guessed_offset = 0;
		int y_guessed_offset = 0;
		int z_guessed_offset = 0;
		for (int i = 0; i < pathAndFillManager.size(); ++i) {
			final Path p = pathAndFillManager.getPath(i);
			for (int j = 0; j < p.size(); ++j) {
				cropped_canvas_x = Math.max(cropped_canvas_x, p.getXUnscaled(j));
				cropped_canvas_y = Math.max(cropped_canvas_y, p.getYUnscaled(j));
				cropped_canvas_z = Math.max(cropped_canvas_z, p.getZUnscaled(j));
				if (guessOffsets) {
					x_guessed_offset = Math.min(x_guessed_offset, p.getXUnscaled(j));
					y_guessed_offset = Math.min(y_guessed_offset, p.getYUnscaled(j));
					z_guessed_offset = Math.min(z_guessed_offset, p.getZUnscaled(j));
				}

			}
		}
		// Padding" is essential to accommodate for "rounding"
		// errors in PathAndFillManager.setPathPointsInVolume()
		width = cropped_canvas_x + 10;
		height = cropped_canvas_y + 10;
		depth = (cropped_canvas_z==1) ? 1 : cropped_canvas_z + 2;

		// Define spatial calibration of stack. We must initialize
		// stacks.ThreePanes.xy to avoid a NPE later on
		final Calibration cal = new Calibration();
		cal.setUnit(voxelUnit);
		cal.pixelWidth = voxelWidth;
		cal.pixelHeight = voxelHeight;
		cal.pixelDepth = voxelDepth;
		xy = new ImagePlus();
		xy.setCalibration(cal);

		try {

			switch (rendingChoice) {
			case UNTAGGED_SKEL:
				// tracing.SimpleNeuriteTracer.makePathVolume() will adopt
				// stacks.ThreePanes.xy's calibration
				final ImagePlus imp = makePathVolume();
				imp.setTitle(chosenFile.getName());
				imp.show();
				break;
			case GRAY_3DVIEWER:
				renderPathsIn3DViewer(false);
				break;
			case COLOR_3DVIEWER:
				renderPathsIn3DViewer(true);
				break;
			default:
				IJ.log("Bug: Unknown option...");
				return;
			}

		} catch (final Exception e) {

			final String RERUN_FLAG = "rerun";
			if (IJ.macroRunning() || arg.equals(RERUN_FLAG)) {
				IPNAT.handleException(e);
				return;
			}

			if (guessOffsets && chosenFile!=null 
					&& new YesNoCancelDialog(IJ.getInstance(),
							"Unable to render " + chosenFile.getName(),
							"Re-try with guessed (presumably more suitable) settings?").yesPressed()) {
				applyScale = false;
				applyOffset = true;
				xOffset = (x_guessed_offset==0d) ? 0d : x_guessed_offset * -1.05;
				yOffset = (y_guessed_offset==0d) ? 0d : y_guessed_offset * -1.05;
				zOffset = (z_guessed_offset==0d) ? 0d : z_guessed_offset * -1.05;
				saveDialogSettings();
				if (Recorder.record) {
					Recorder.setCommand(Recorder.getCommand());
					Recorder.recordPath("open", chosenFile.getAbsolutePath());
				}
				run(RERUN_FLAG);
			}

		}

	}

	private synchronized void renderPathsIn3DViewer(final boolean colorize) {
		univ = get3DUniverse();
		if (univ == null) {
			univ = new Image3DUniverse(width, height);
		}
		for (int i = 0; i < pathAndFillManager.size(); ++i) {
			final Path p = pathAndFillManager.getPath(i);
			final Color color = getSWCcolor(colorize ? p.getSWCType() : Path.SWC_UNDEFINED);
			p.addTo3DViewer(univ, color, colorImage);
		}
		univ.show();
		GUI.center(univ.getWindow());
	}

	private Color getSWCcolor(final int swcType) {
		switch (swcType) {
		case Path.SWC_SOMA:
			return Color.MAGENTA;
		case Path.SWC_DENDRITE:
			return Color.GREEN;
		case Path.SWC_APICAL_DENDRITE:
			return Color.CYAN;
		case Path.SWC_AXON:
			return Color.BLUE;
		case Path.SWC_FORK_POINT:
			return Color.MAGENTA;
		case Path.SWC_END_POINT:
			return Color.PINK;
		case Path.SWC_CUSTOM:
			return Color.YELLOW;
		case Path.SWC_UNDEFINED:
		default:
			return Color.WHITE;
		}
	}

	/**
	 * Gets import settings from user.
	 *
	 * The easiest would be to use SWCImportOptionsDialog, however it has
	 * several disadvantages: It is not macro recordable, there isn't an
	 * immediate way to find out if the dialog has been dismissed by the user
	 * and it does not allow for input of spatial calibrations and t. So we'll
	 * have to re-implement most of it here using IJ's recordable GenericDialog.
	 * For consistency, We will keep the same preference keys used by Simple
	 * Neurite Tracer.
	 *
	 * @return {@code true} if user OKed dialog prompt, otherwise {@code false}
	 */
	private boolean getImportSettingsFromUser() {
		loadDialogSettings();
		settingsDialog = new EnhancedGenericDialog("SWC import options");
		settingsDialog.addCheckbox("Apply_calibration to SWC file coordinates:", !ignoreCalibration);
		settingsDialog.addNumericField("             Voxel_width", voxelWidth, 2);
		settingsDialog.addNumericField("Voxel_height", voxelHeight, 2);
		settingsDialog.addNumericField("Voxel_depth", voxelDepth, 2);
		settingsDialog.addStringField("Unit", voxelUnit, 6);
		settingsDialog.addCheckbox("Apply_offset to SWC file coordinates:", applyOffset);
		settingsDialog.addNumericField("X_offset", xOffset, 2);
		settingsDialog.addNumericField("Y_offset", yOffset, 2);
		settingsDialog.addNumericField("Z_offset", zOffset, 2);
		settingsDialog.addCheckbox("Apply_scale to SWC file coordinates:", applyScale);
		settingsDialog.addNumericField("X_scale", xScale, 2);
		settingsDialog.addNumericField("Y_scale", yScale, 2);
		settingsDialog.addNumericField("Z_scale", zScale, 2);
		settingsDialog.addMessage(""); // spacer
		settingsDialog.addChoice("Render as:", RENDING_OPTIONS, RENDING_OPTIONS[rendingChoice]);

		// Add listener anf update prompt
		settingsDialog.addDialogListener(this);
		dialogItemChanged(settingsDialog, null);

		// Add More>> dropdown menu
		final JPopupMenu popup = new JPopupMenu();
		JMenuItem mi;
		mi = new JMenuItem("Restore default options");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				dialogItemChanged(settingsDialog, e);
			}
		});
		popup.add(mi);
		popup.addSeparator();
		mi = new JMenuItem("About hIPNAT plugins...");
		mi.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				dialogItemChanged(settingsDialog, e);
			}
		});
		popup.add(mi);
		settingsDialog.assignPopupToHelpButton(popup);
		settingsDialog.showDialog();

		return settingsDialog.wasOKed();
	}

	
	/* (non-Javadoc)
	 * @see ij.gui.DialogListener#dialogItemChanged(ij.gui.GenericDialog, java.awt.AWTEvent)
	 */
	@Override
	public boolean dialogItemChanged(final GenericDialog gd, final AWTEvent e) {

		if (e != null && e.toString().contains("About")) {
			IJ.runPlugIn("ipnat.Help", "");
			return true;
		} else if (e != null && e.toString().contains("Restore")) {
			resetPreferences();
			loadDialogSettings();
			final Vector<?> checkboxes = gd.getCheckboxes();
			for (int i = 0; i < checkboxes.size(); i++)
				((Checkbox) checkboxes.elementAt(i)).setState(false);
		} else {
			setSettingsFromDialog();
		}
		final Vector<?> nFields = gd.getNumericFields();
		final Vector<?> sFields = gd.getStringFields();
		for (int i = 0; i < 3; i++)
			((Component) nFields.elementAt(i)).setEnabled(!ignoreCalibration);
		((Component) sFields.elementAt(0)).setEnabled(!ignoreCalibration);
		for (int i = 3; i < 6; i++)
			((Component) nFields.elementAt(i)).setEnabled(applyOffset);
		for (int i = 6; i < 9; i++)
			((Component) nFields.elementAt(i)).setEnabled(applyScale);

		return true;
	}

	private void setSettingsFromDialog() {
		ignoreCalibration = !settingsDialog.getNextBoolean();
		voxelWidth = settingsDialog.getNextNumber();
		voxelHeight = settingsDialog.getNextNumber();
		voxelDepth = settingsDialog.getNextNumber();
		voxelUnit = settingsDialog.getNextString();

		applyOffset = settingsDialog.getNextBoolean();
		xOffset = settingsDialog.getNextNumber();
		yOffset = settingsDialog.getNextNumber();
		zOffset = settingsDialog.getNextNumber();

		applyScale = settingsDialog.getNextBoolean();
		xScale = Math.max(0.01, settingsDialog.getNextNumber());
		yScale = Math.max(0.01, settingsDialog.getNextNumber());
		zScale = Math.max(0.01, settingsDialog.getNextNumber());

		rendingChoice = settingsDialog.getNextChoiceIndex();
	}

	private void resetPreferences() {
		Prefs.set(PREFS_KEY + "applyOffset", null);
		Prefs.set(PREFS_KEY + "xOffset", null);
		Prefs.set(PREFS_KEY + "yOffset", null);
		Prefs.set(PREFS_KEY + "zOffset", null);
		Prefs.set(PREFS_KEY + "applyScale", null);
		Prefs.set(PREFS_KEY + "xScale", null);
		Prefs.set(PREFS_KEY + "yScale", null);
		Prefs.set(PREFS_KEY + "zScale", null);
		Prefs.set(PREFS_KEY + "voxelCalibration", null);
	}

	private void loadDialogSettings() {
		final String defaultBoolean = String.valueOf(Boolean.FALSE);
		ignoreCalibration = Boolean.parseBoolean(Prefs.get(PREFS_KEY + "ignoreCalibration", defaultBoolean));
		getCalibrationFromPrefs();
		applyOffset = Boolean.parseBoolean(Prefs.get(PREFS_KEY + "applyOffset", defaultBoolean));
		xOffset = Double.parseDouble(Prefs.get(PREFS_KEY + "xOffset", String.valueOf(DEFAULT_OFFSET)));
		yOffset = Double.parseDouble(Prefs.get(PREFS_KEY + "yOffset", String.valueOf(DEFAULT_OFFSET)));
		zOffset = Double.parseDouble(Prefs.get(PREFS_KEY + "zOffset", String.valueOf(DEFAULT_OFFSET)));
		applyScale = Boolean.parseBoolean(Prefs.get(PREFS_KEY + ".applyScale", defaultBoolean));
		xScale = Double.parseDouble(Prefs.get(PREFS_KEY + "xScale", String.valueOf(DEFAULT_SCALE)));
		yScale = Double.parseDouble(Prefs.get(PREFS_KEY + "yScale", String.valueOf(DEFAULT_SCALE)));
		zScale = Double.parseDouble(Prefs.get(PREFS_KEY + "zScale", String.valueOf(DEFAULT_SCALE)));
	}

	void getCalibrationFromPrefs() {
		final String defaults = DEF_VOXEL_WIDTH + "," + DEF_VOXEL_HEIGHT + "," + DEF_VOXEL_DEPTH + "," + DEF_VOXEL_UNIT;
		try {
			final String[] values = Tools.split(Prefs.get(PREFS_KEY + "voxelCalibration", defaults), ",");
			voxelWidth = Double.parseDouble(values[0]);
			voxelHeight = Double.parseDouble(values[1]);
			voxelDepth = Double.parseDouble(values[2]);
			voxelUnit = values[3];
		} catch (Exception ignored) {
			voxelWidth = DEF_VOXEL_WIDTH;
			voxelHeight = DEF_VOXEL_HEIGHT;
			voxelDepth = DEF_VOXEL_DEPTH;
			voxelUnit = DEF_VOXEL_UNIT;
		}
	}

	private void saveDialogSettings() {
		Prefs.set(PREFS_KEY + "ignoreCalibration", String.valueOf(ignoreCalibration));
		Prefs.set(PREFS_KEY + "voxelCalibration", ignoreCalibration ? null
				: voxelWidth + "," + voxelHeight + "," + voxelDepth + "," + voxelUnit.replace(",", ""));
		Prefs.set(PREFS_KEY + "applyOffset", String.valueOf(applyOffset));
		Prefs.set(PREFS_KEY + "xOffset", applyOffset ? String.valueOf(xOffset) : null);
		Prefs.set(PREFS_KEY + "yOffset", applyOffset ? String.valueOf(yOffset) : null);
		Prefs.set(PREFS_KEY + "zOffset", applyOffset ? String.valueOf(zOffset) : null);
		Prefs.set(PREFS_KEY + "applyScale", String.valueOf(applyScale));
		Prefs.set(PREFS_KEY + "xScale", applyScale ? String.valueOf(xScale) : null);
		Prefs.set(PREFS_KEY + "yScale", applyScale ? String.valueOf(yScale) : null);
		Prefs.set(PREFS_KEY + "zScale", applyScale ? String.valueOf(zScale) : null);
	}

}
