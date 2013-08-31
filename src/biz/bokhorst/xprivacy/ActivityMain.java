package biz.bokhorst.xprivacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class ActivityMain extends Activity implements OnItemSelectedListener, CompoundButton.OnCheckedChangeListener {
	private int mThemeId;
	private Spinner spRestriction = null;
	private AppListAdapter mAppAdapter = null;
	private String mUserSystemOrBoth = APP_FILTER_BOTH;
	private boolean mFiltersHidden = false;

	private static final String APP_FILTER_BOTH = "B";
	private static final String APP_FILTER_USER = "U";
	private static final String APP_FILTER_SYS = "S";

	private static final int ACTIVITY_LICENSE = 0;
	private static final int ACTIVITY_EXPORT = 1;
	private static final int ACTIVITY_IMPORT = 2;
	private static final int ACTIVITY_IMPORT_SELECT = 3;

	private static final int LICENSED = 0x0100;
	private static final int NOT_LICENSED = 0x0231;
	private static final int RETRY = 0x0123;

	private static final int ERROR_CONTACTING_SERVER = 0x101;
	private static final int ERROR_INVALID_PACKAGE_NAME = 0x102;
	private static final int ERROR_NON_MATCHING_UID = 0x103;

	private static ExecutorService mExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
			new PriorityThreadFactory());

	private static class PriorityThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setPriority(Thread.NORM_PRIORITY);
			return t;
		}
	}

	private BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			ActivityMain.this.recreate();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Salt should be the same when exporting/importing
		String salt = PrivacyManager.getSetting(null, this, 0, PrivacyManager.cSettingSalt, null, false);
		if (salt == null) {
			salt = Build.SERIAL;
			if (salt == null)
				salt = "";
			PrivacyManager.setSetting(null, this, 0, PrivacyManager.cSettingSalt, salt);
		}

		// Set theme
		String themeName = PrivacyManager.getSetting(null, this, 0, PrivacyManager.cSettingTheme, "", false);
		mThemeId = (themeName.equals("Dark") ? R.style.CustomTheme : R.style.CustomTheme_Light);
		setTheme(mThemeId);

		// Set layout
		setContentView(R.layout.mainlist);
		getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

		// Get localized restriction name
		List<String> listRestriction = PrivacyManager.getRestrictions(true);
		List<String> listLocalizedRestriction = new ArrayList<String>();
		for (String restrictionName : listRestriction)
			listLocalizedRestriction.add(PrivacyManager.getLocalizedName(this, restrictionName));
		listLocalizedRestriction.add(0, getString(R.string.menu_all));

		// Build spinner adapter
		SpinnerAdapter spAdapter = new SpinnerAdapter(this, android.R.layout.simple_spinner_item);
		spAdapter.addAll(listLocalizedRestriction);

		// Handle info
		ImageView imgInfo = (ImageView) findViewById(R.id.imgInfo);
		imgInfo.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				int position = spRestriction.getSelectedItemPosition();
				if (position != AdapterView.INVALID_POSITION) {
					String title = (position == 0 ? "XPrivacy" : PrivacyManager.getRestrictions(true).get(position - 1));
					String url = String.format("http://wiki.faircode.eu/index.php?title=%s", title);
					Intent infoIntent = new Intent(Intent.ACTION_VIEW);
					infoIntent.setData(Uri.parse(url));
					startActivity(infoIntent);
				}
			}
		});

		// Setup spinner
		spRestriction = (Spinner) findViewById(R.id.spRestriction);
		spRestriction.setAdapter(spAdapter);
		spRestriction.setOnItemSelectedListener(this);

		// Setup used filter
		CheckBox cbUsed = (CheckBox) findViewById(R.id.cbFUsed);
		cbUsed.setOnCheckedChangeListener(this);

		// Setup internet filter
		CheckBox cbInternet = (CheckBox) findViewById(R.id.cbFInternet);
		cbInternet.setOnCheckedChangeListener(this);

		// Setup name filter
		final EditText etFilter = (EditText) findViewById(R.id.etFilter);
		etFilter.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String text = etFilter.getText().toString();
				ImageView imgClear = (ImageView) findViewById(R.id.imgClear);
				imgClear.setImageDrawable(getResources().getDrawable(
						getThemed(text.equals("") ? R.attr.icon_clear_grayed : R.attr.icon_clear)));
				applyFilter();
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});

		ImageView imgClear = (ImageView) findViewById(R.id.imgClear);
		imgClear.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				etFilter.setText("");
			}
		});

		// Setup restriction filter
		CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
		cbFilter.setOnCheckedChangeListener(this);

		// Setup permission filter
		boolean fPermission = PrivacyManager.getSettingBool(null, ActivityMain.this, 0,
				PrivacyManager.cSettingFPermission, true, false);
		CheckBox cbFPermission = (CheckBox) findViewById(R.id.cbFPermission);
		cbFPermission.setChecked(fPermission);
		cbFPermission.setOnCheckedChangeListener(this);

		// Setup user/system/both filter
		mUserSystemOrBoth = PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingFSystem,
				APP_FILTER_BOTH, false);
		final ImageView imgUserSystemBoth = (ImageView) findViewById(R.id.imgAppFilter);
		imgUserSystemBoth.setImageDrawable(getResources().getDrawable(
				getThemed(mUserSystemOrBoth.equals(APP_FILTER_SYS) ? R.attr.icon_system : (mUserSystemOrBoth
						.equals(APP_FILTER_USER) ? R.attr.icon_user : R.attr.icon_user_system))));
		final LinearLayout llAppFilter = (LinearLayout) findViewById(R.id.llAppFilter);
		final LinearLayout llAppFilter2 = (LinearLayout) findViewById(R.id.llAppFilter2);
		final CheckBox cbFSystem = (CheckBox) findViewById(R.id.cbFSystem);
		cbFSystem.setChecked(!mUserSystemOrBoth.equals(APP_FILTER_BOTH));
		cbFSystem.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				llAppFilter.setVisibility(LinearLayout.GONE);
				llAppFilter2.setVisibility(LinearLayout.VISIBLE);
			}
		});

		// All
		final ImageView imgAppAll = (ImageView) findViewById(R.id.imgAppAll);
		imgAppAll.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				mUserSystemOrBoth = APP_FILTER_BOTH;
				PrivacyManager
						.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingFSystem, mUserSystemOrBoth);
				imgUserSystemBoth.setImageDrawable(getResources().getDrawable(getThemed(R.attr.icon_user_system)));
				llAppFilter.setVisibility(LinearLayout.VISIBLE);
				llAppFilter2.setVisibility(LinearLayout.GONE);
				cbFSystem.setChecked(false);
				applyFilter();
			}
		});

		// System
		final ImageView imgAppSystem = (ImageView) findViewById(R.id.imgAppSys);
		imgAppSystem.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				mUserSystemOrBoth = APP_FILTER_SYS;
				PrivacyManager
						.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingFSystem, mUserSystemOrBoth);
				imgUserSystemBoth.setImageDrawable(getResources().getDrawable(getThemed(R.attr.icon_system)));
				llAppFilter.setVisibility(LinearLayout.VISIBLE);
				llAppFilter2.setVisibility(LinearLayout.GONE);
				cbFSystem.setChecked(true);
				applyFilter();
			}
		});

		// User
		final ImageView imgAppUser = (ImageView) findViewById(R.id.imgAppUser);
		imgAppUser.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				mUserSystemOrBoth = APP_FILTER_USER;
				PrivacyManager
						.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingFSystem, mUserSystemOrBoth);
				imgUserSystemBoth.setImageDrawable(getResources().getDrawable(getThemed(R.attr.icon_user)));
				llAppFilter.setVisibility(LinearLayout.VISIBLE);
				llAppFilter2.setVisibility(LinearLayout.GONE);
				cbFSystem.setChecked(true);
				applyFilter();
			}
		});

		// Hide filters
		if (savedInstanceState != null && savedInstanceState.containsKey("Filters"))
			mFiltersHidden = !savedInstanceState.getBoolean("Filters");
		toggleFiltersVisibility();

		// Handle toggle filters visibility
		ImageView imgFilterToggle = (ImageView) findViewById(R.id.imgToggleFilters);
		imgFilterToggle.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				toggleFiltersVisibility();
			}
		});

		// Start task to get app list
		AppListTask appListTask = new AppListTask();
		appListTask.executeOnExecutor(mExecutor, (Object) null);

		// Check environment
		Requirements.check(this);

		// Licensing
		checkLicense();

		// Listen for package add/remove
		IntentFilter iff = new IntentFilter();
		iff.addAction(Intent.ACTION_PACKAGE_ADDED);
		iff.addAction(Intent.ACTION_PACKAGE_REMOVED);
		iff.addDataScheme("package");
		registerReceiver(mPackageChangeReceiver, iff);

		// First run
		if (PrivacyManager.getSettingBool(null, this, 0, PrivacyManager.cSettingFirstRun, true, false)) {
			optionAbout();
			PrivacyManager.setSetting(null, this, 0, PrivacyManager.cSettingFirstRun, Boolean.FALSE.toString());
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean("Filters", mFiltersHidden);
		super.onSaveInstanceState(outState);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (mAppAdapter != null)
			mAppAdapter.notifyDataSetChanged();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (mPackageChangeReceiver != null)
			unregisterReceiver(mPackageChangeReceiver);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == ACTIVITY_LICENSE) {
			// Result for license check
			if (data != null) {
				int code = data.getIntExtra("Code", -1);
				int reason = data.getIntExtra("Reason", -1);

				String sReason;
				if (reason == LICENSED)
					sReason = "LICENSED";
				else if (reason == NOT_LICENSED)
					sReason = "NOT_LICENSED";
				else if (reason == RETRY)
					sReason = "RETRY";
				else if (reason == ERROR_CONTACTING_SERVER)
					sReason = "ERROR_CONTACTING_SERVER";
				else if (reason == ERROR_INVALID_PACKAGE_NAME)
					sReason = "ERROR_INVALID_PACKAGE_NAME";
				else if (reason == ERROR_NON_MATCHING_UID)
					sReason = "ERROR_NON_MATCHING_UID";
				else
					sReason = Integer.toString(reason);

				Util.log(null, Log.INFO, "Licensing: code=" + code + " reason=" + sReason);

				if (code > 0) {
					Util.setPro(true);
					invalidateOptionsMenu();
					Toast toast = Toast.makeText(this, getString(R.string.menu_pro), Toast.LENGTH_LONG);
					toast.show();
				} else if (reason == RETRY) {
					Util.setPro(false);
					new Handler().postDelayed(new Runnable() {
						@Override
						public void run() {
							checkLicense();
						}
					}, 60 * 1000);
				}
			}
		} else if (requestCode == ACTIVITY_EXPORT) {
			// Exported: send share intent
			if (data != null && data.hasExtra(ActivityShare.cFileName)) {
				Intent intent = new Intent(android.content.Intent.ACTION_SEND);
				intent.setType("text/xml");
				intent.putExtra(Intent.EXTRA_STREAM,
						Uri.parse("file://" + data.getStringExtra(ActivityShare.cFileName)));
				startActivity(Intent.createChooser(intent, getString(R.string.app_name)));
			}
		} else if (requestCode == ACTIVITY_IMPORT) {
			// Imported: recreate UI
			ActivityMain.this.recreate();
		} else if (requestCode == ACTIVITY_IMPORT_SELECT) {
			// Result for import file choice
			if (data != null)
				try {
					String fileName = data.getData().getPath();
					Intent intent = new Intent("biz.bokhorst.xprivacy.action.IMPORT");
					intent.putExtra(ActivityShare.cFileName, fileName);
					startActivityForResult(intent, ACTIVITY_IMPORT);
				} catch (Throwable ex) {
					Util.bug(null, ex);
				}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		boolean pro = (Util.hasProLicense(this) != null);
		boolean mounted = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());

		menu.findItem(R.id.menu_export).setEnabled(pro && mounted);
		menu.findItem(R.id.menu_import).setEnabled(pro && mounted);
		menu.findItem(R.id.menu_pro).setVisible(!pro);

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		try {
			switch (item.getItemId()) {
			case R.id.menu_help:
				optionHelp();
				return true;
			case R.id.menu_all:
				optionAll();
				return true;
			case R.id.menu_usage:
				optionUsage();
				return true;
			case R.id.menu_settings:
				optionSettings();
				return true;
			case R.id.menu_template:
				optionTemplate();
				return true;
			case R.id.menu_update:
				optionCheckUpdate();
				return true;
			case R.id.menu_report:
				optionReportIssue();
				return true;
			case R.id.menu_export:
				optionExport();
				return true;
			case R.id.menu_import:
				optionImport();
				return true;
			case R.id.menu_theme:
				optionSwitchTheme();
				return true;
			case R.id.menu_pro:
				optionPro();
				return true;
			case R.id.menu_about:
				optionAbout();
				return true;
			default:
				return super.onOptionsItemSelected(item);
			}
		} catch (Throwable ex) {
			Util.bug(null, ex);
			return true;
		}
	}

	// Filtering

	@Override
	public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
		selectRestriction(pos);
	}

	@Override
	public void onNothingSelected(AdapterView<?> parent) {
		selectRestriction(0);
	}

	private void selectRestriction(int pos) {
		if (mAppAdapter != null) {
			String restrictionName = (pos == 0 ? null : PrivacyManager.getRestrictions(true).get(pos - 1));
			mAppAdapter.setRestrictionName(restrictionName);
			applyFilter();
		}
	}

	@Override
	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
		CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
		CheckBox cbUsed = (CheckBox) findViewById(R.id.cbFUsed);
		CheckBox cbInternet = (CheckBox) findViewById(R.id.cbFInternet);
		CheckBox cbPermission = (CheckBox) findViewById(R.id.cbFPermission);
		if (buttonView == cbFilter || buttonView == cbUsed || buttonView == cbInternet)
			applyFilter();
		else if (buttonView == cbPermission) {
			PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingFPermission,
					Boolean.toString(isChecked));
			selectRestriction(spRestriction.getSelectedItemPosition());
		}
	}

	private void applyFilter() {
		if (mAppAdapter != null) {
			EditText etFilter = (EditText) findViewById(R.id.etFilter);
			CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
			CheckBox cbUsed = (CheckBox) findViewById(R.id.cbFUsed);
			CheckBox cbInternet = (CheckBox) findViewById(R.id.cbFInternet);
			ProgressBar pbFilter = (ProgressBar) findViewById(R.id.pbFilter);
			TextView tvStats = (TextView) findViewById(R.id.tvStats);
			String filter = String.format("%b\n%b\n%s\n%b\n%s", cbUsed.isChecked(), cbInternet.isChecked(), etFilter
					.getText().toString(), cbFilter.isChecked(), mUserSystemOrBoth);
			pbFilter.setVisibility(ProgressBar.VISIBLE);
			tvStats.setVisibility(TextView.GONE);
			mAppAdapter.getFilter().filter(filter);
		}
	}

	// Options

	private void optionAll() {
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		alertDialogBuilder.setTitle(getString(R.string.app_name));
		alertDialogBuilder.setMessage(getString(R.string.msg_sure));
		alertDialogBuilder.setIcon(getThemed(R.attr.icon_launcher));
		alertDialogBuilder.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (mAppAdapter != null) {
					// Check if some restricted
					boolean someRestricted = false;
					for (int pos = 0; pos < mAppAdapter.getCount(); pos++) {
						ApplicationInfoEx xAppInfo = mAppAdapter.getItem(pos);
						if (mAppAdapter.getRestrictionName() == null) {
							for (boolean restricted : PrivacyManager.getRestricted(getApplicationContext(),
									xAppInfo.getUid(), false))
								if (restricted) {
									someRestricted = true;
									break;
								}
						} else if (PrivacyManager.getRestricted(null, ActivityMain.this, xAppInfo.getUid(),
								mAppAdapter.getRestrictionName(), null, false, false))
							someRestricted = true;
						if (someRestricted)
							break;
					}

					// Invert selection
					for (int pos = 0; pos < mAppAdapter.getCount(); pos++) {
						ApplicationInfoEx xAppInfo = mAppAdapter.getItem(pos);
						if (mAppAdapter.getRestrictionName() == null) {
							for (String restrictionName : PrivacyManager.getRestrictions(false))
								PrivacyManager.setRestricted(null, ActivityMain.this, xAppInfo.getUid(),
										restrictionName, null, !someRestricted);
						} else
							PrivacyManager.setRestricted(null, ActivityMain.this, xAppInfo.getUid(),
									mAppAdapter.getRestrictionName(), null, !someRestricted);
					}

					// Refresh
					mAppAdapter.notifyDataSetChanged();
				}
			}
		});
		alertDialogBuilder.setNegativeButton(getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
			}
		});
		AlertDialog alertDialog = alertDialogBuilder.create();
		alertDialog.show();
	}

	private void optionUsage() {
		Intent intent = new Intent(this, ActivityUsage.class);
		startActivity(intent);
	}

	private void optionSettings() {
		// Build dialog
		final Dialog dlgSettings = new Dialog(this);
		dlgSettings.requestWindowFeature(Window.FEATURE_LEFT_ICON);
		dlgSettings.setTitle(getString(R.string.app_name));
		dlgSettings.setContentView(R.layout.settings);
		dlgSettings.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, getThemed(R.attr.icon_launcher));

		// Reference controls
		final EditText etSerial = (EditText) dlgSettings.findViewById(R.id.etSerial);
		final EditText etLat = (EditText) dlgSettings.findViewById(R.id.etLat);
		final EditText etLon = (EditText) dlgSettings.findViewById(R.id.etLon);
		final EditText etSearch = (EditText) dlgSettings.findViewById(R.id.etSearch);
		Button btnSearch = (Button) dlgSettings.findViewById(R.id.btnSearch);
		final EditText etMac = (EditText) dlgSettings.findViewById(R.id.etMac);
		final EditText etIP = (EditText) dlgSettings.findViewById(R.id.etIP);
		final EditText etImei = (EditText) dlgSettings.findViewById(R.id.etImei);
		final EditText etPhone = (EditText) dlgSettings.findViewById(R.id.etPhone);
		final EditText etId = (EditText) dlgSettings.findViewById(R.id.etId);
		final EditText etGsfId = (EditText) dlgSettings.findViewById(R.id.etGsfId);
		final EditText etMcc = (EditText) dlgSettings.findViewById(R.id.etMcc);
		final EditText etMnc = (EditText) dlgSettings.findViewById(R.id.etMnc);
		final EditText etCountry = (EditText) dlgSettings.findViewById(R.id.etCountry);
		final EditText etOperator = (EditText) dlgSettings.findViewById(R.id.etOperator);
		final EditText etIccId = (EditText) dlgSettings.findViewById(R.id.etIccId);
		final EditText etSubscriber = (EditText) dlgSettings.findViewById(R.id.etSubscriber);
		final EditText etUa = (EditText) dlgSettings.findViewById(R.id.etUa);
		final CheckBox cbLog = (CheckBox) dlgSettings.findViewById(R.id.cbLog);
		final Button btnRandom = (Button) dlgSettings.findViewById(R.id.btnRandom);
		final CheckBox cbRandom = (CheckBox) dlgSettings.findViewById(R.id.cbRandom);
		Button btnOk = (Button) dlgSettings.findViewById(R.id.btnOk);

		// Set current values
		boolean log = PrivacyManager.getSettingBool(null, ActivityMain.this, 0, PrivacyManager.cSettingLog, false,
				false);
		boolean random = PrivacyManager.getSettingBool(null, ActivityMain.this, 0, PrivacyManager.cSettingRandom,
				false, false);

		etSerial.setText(PrivacyManager
				.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingSerial, "", false));
		etLat.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLatitude, "", false));
		etLon.setText(PrivacyManager
				.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLongitude, "", false));
		etMac.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMac, "", false));
		etIP.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingIP, "", false));
		etImei.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingImei, "", false));
		etPhone.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingPhone, "", false));
		etId.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingId, "", false));
		etGsfId.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingGsfId, "", false));
		etMcc.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMcc, "", false));
		etMnc.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMnc, "", false));
		etCountry.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingCountry, "",
				false));
		etOperator.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingOperator, "",
				false));
		etIccId.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingIccId, "", false));
		etSubscriber.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingSubscriber,
				"", false));
		etUa.setText(PrivacyManager.getSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingUa, "", false));
		cbLog.setChecked(log);
		cbRandom.setChecked(random);

		// Handle search
		etSearch.setEnabled(Geocoder.isPresent());
		btnSearch.setEnabled(Geocoder.isPresent());
		btnSearch.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				try {
					etLat.setText("");
					etLon.setText("");
					String search = etSearch.getText().toString();
					final List<Address> listAddress = new Geocoder(ActivityMain.this).getFromLocationName(search, 1);
					if (listAddress.size() > 0) {
						Address address = listAddress.get(0);

						// Get coordinates
						if (address.hasLatitude())
							etLat.setText(Double.toString(address.getLatitude()));
						if (address.hasLongitude())
							etLon.setText(Double.toString(address.getLongitude()));

						// Get address
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
							if (i != 0)
								sb.append(", ");
							sb.append(address.getAddressLine(i));
						}
						etSearch.setText(sb.toString());
					}
				} catch (Throwable ex) {
					Util.bug(null, ex);
				}
			}
		});

		// Handle randomize
		btnRandom.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				etLat.setText(PrivacyManager.getRandomProp("LAT"));
				etLon.setText(PrivacyManager.getRandomProp("LON"));
				etSerial.setText(PrivacyManager.getRandomProp("SERIAL"));
				etMac.setText(PrivacyManager.getRandomProp("MAC"));
				etPhone.setText(PrivacyManager.getRandomProp("PHONE"));
				etImei.setText(PrivacyManager.getRandomProp("IMEI"));
				etId.setText(PrivacyManager.getRandomProp("ANDROID_ID"));
				etGsfId.setText(PrivacyManager.getRandomProp("GSF_ID"));
				etCountry.setText(PrivacyManager.getRandomProp("ISO3166"));
			}
		});

		// Handle OK
		btnOk.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				// Serial#
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingSerial, etSerial.getText()
						.toString());

				// Set location
				try {
					float lat = Float.parseFloat(etLat.getText().toString().replace(',', '.'));
					float lon = Float.parseFloat(etLon.getText().toString().replace(',', '.'));
					if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
						throw new InvalidParameterException();

					PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLatitude,
							Float.toString(lat));
					PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLongitude,
							Float.toString(lon));

				} catch (Throwable ex) {
					PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLatitude, "");
					PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLongitude, "");
				}

				// Other settings
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMac, etMac.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingIP, etIP.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingImei, etImei.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingPhone, etPhone.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingId, etId.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingGsfId, etGsfId.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMcc, etMcc.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingMnc, etMnc.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingCountry, etCountry
						.getText().toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingOperator, etOperator
						.getText().toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingIccId, etIccId.getText()
						.toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingSubscriber, etSubscriber
						.getText().toString());
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingUa, etUa.getText()
						.toString());

				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingLog,
						Boolean.toString(cbLog.isChecked()));
				PrivacyManager.setSetting(null, ActivityMain.this, 0, PrivacyManager.cSettingRandom,
						Boolean.toString(cbRandom.isChecked()));

				// Done
				dlgSettings.dismiss();
			}
		});

		dlgSettings.setCancelable(true);
		dlgSettings.show();
	}

	private void optionTemplate() {
		// Get restriction categories
		final List<String> listRestriction = PrivacyManager.getRestrictions(false);
		CharSequence[] options = new CharSequence[listRestriction.size()];
		boolean[] selection = new boolean[listRestriction.size()];
		for (int i = 0; i < listRestriction.size(); i++) {
			options[i] = PrivacyManager.getLocalizedName(this, listRestriction.get(i));
			selection[i] = PrivacyManager.getSettingBool(null, this, 0,
					String.format("Template.%s", listRestriction.get(i)), true, false);
		}

		// Build dialog
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		alertDialogBuilder.setTitle(getString(R.string.menu_template));
		alertDialogBuilder.setIcon(getThemed(R.attr.icon_launcher));
		alertDialogBuilder.setMultiChoiceItems(options, selection, new DialogInterface.OnMultiChoiceClickListener() {
			public void onClick(DialogInterface dialog, int whichButton, boolean isChecked) {
				PrivacyManager.setSetting(null, ActivityMain.this, 0,
						String.format("Template.%s", listRestriction.get(whichButton)), Boolean.toString(isChecked));
			}
		});
		alertDialogBuilder.setPositiveButton(getString(R.string.msg_done), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				// Do nothing
			}
		});

		// Show dialog
		AlertDialog alertDialog = alertDialogBuilder.create();
		alertDialog.show();
	}

	private void optionCheckUpdate() {
		new UpdateTask().executeOnExecutor(mExecutor, "http://goo.im/json2&path=/devs/M66B/xprivacy");
	}

	private void optionReportIssue() {
		// Report issue
		Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/M66B/XPrivacy/issues"));
		startActivity(browserIntent);
	}

	private void optionExport() {
		boolean multiple = Util.isIntentAvailable(ActivityMain.this, Intent.ACTION_GET_CONTENT);
		Intent intent = new Intent("biz.bokhorst.xprivacy.action.EXPORT");
		intent.putExtra(ActivityShare.cFileName, ActivityShare.getFileName(multiple));
		startActivityForResult(intent, ACTIVITY_EXPORT);
	}

	private void optionImport() {
		if (Util.isIntentAvailable(ActivityMain.this, Intent.ACTION_GET_CONTENT)) {
			Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
			Uri uri = Uri.parse(Environment.getExternalStorageDirectory().getPath() + "/.xprivacy/");
			chooseFile.setDataAndType(uri, "text/xml");
			Intent intent = Intent.createChooser(chooseFile, getString(R.string.app_name));
			startActivityForResult(intent, ACTIVITY_IMPORT_SELECT);
		} else {
			Intent intent = new Intent("biz.bokhorst.xprivacy.action.IMPORT");
			intent.putExtra(ActivityShare.cFileName, ActivityShare.getFileName(false));
			startActivityForResult(intent, ACTIVITY_IMPORT);
		}
	}

	private void optionSwitchTheme() {
		String themeName = PrivacyManager.getSetting(null, this, 0, PrivacyManager.cSettingTheme, "", false);
		themeName = (themeName.equals("Dark") ? "Light" : "Dark");
		PrivacyManager.setSetting(null, this, 0, PrivacyManager.cSettingTheme, themeName);
		this.recreate();
	}

	private void optionPro() {
		// Redirect to pro page
		Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.faircode.eu/xprivacy/"));
		startActivity(browserIntent);
	}

	private void optionAbout() {
		// About
		Dialog dlgAbout = new Dialog(this);
		dlgAbout.requestWindowFeature(Window.FEATURE_LEFT_ICON);
		dlgAbout.setTitle(getString(R.string.app_name));
		dlgAbout.setContentView(R.layout.about);
		dlgAbout.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, getThemed(R.attr.icon_launcher));

		// Show version
		try {
			PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			TextView tvVersion = (TextView) dlgAbout.findViewById(R.id.tvVersion);
			tvVersion.setText(String.format(getString(R.string.app_version), pInfo.versionName, pInfo.versionCode));
		} catch (Throwable ex) {
			Util.bug(null, ex);
		}

		// Show Xposed version
		int xVersion = Util.getXposedVersion();
		TextView tvXVersion = (TextView) dlgAbout.findViewById(R.id.tvXVersion);
		tvXVersion.setText(String.format(getString(R.string.app_xversion), xVersion));

		// Show license
		String licensed = Util.hasProLicense(this);
		TextView tvLicensed = (TextView) dlgAbout.findViewById(R.id.tvLicensed);
		if (licensed == null)
			tvLicensed.setVisibility(View.GONE);
		else
			tvLicensed.setText(String.format(getString(R.string.msg_licensed), licensed));

		dlgAbout.setCancelable(true);
		dlgAbout.show();
	}

	private void optionHelp() {
		// Show help
		Dialog dialog = new Dialog(ActivityMain.this);
		dialog.requestWindowFeature(Window.FEATURE_LEFT_ICON);
		dialog.setTitle(getString(R.string.help_application));
		dialog.setContentView(R.layout.helpmain);
		dialog.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, getThemed(R.attr.icon_launcher));
		dialog.setCancelable(true);
		dialog.show();
	}

	private void toggleFiltersVisibility() {
		ImageView imgFilterToggle = (ImageView) findViewById(R.id.imgToggleFilters);
		ImageView imgClear = (ImageView) findViewById(R.id.imgClear);
		TextView tvFilters = (TextView) findViewById(R.id.tvFilterDetail);
		EditText etFilter = (EditText) findViewById(R.id.etFilter);
		LinearLayout llFilters = (LinearLayout) findViewById(R.id.llFilters);
		CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
		CheckBox cbFUsed = (CheckBox) findViewById(R.id.cbFUsed);
		CheckBox cbFInternet = (CheckBox) findViewById(R.id.cbFInternet);
		CheckBox cbFPermission = (CheckBox) findViewById(R.id.cbFPermission);

		if (mFiltersHidden) {
			// Change visibility
			tvFilters.setVisibility(TextView.GONE);
			etFilter.setVisibility(EditText.VISIBLE);
			imgClear.setVisibility(ImageView.VISIBLE);
			llFilters.setVisibility(LinearLayout.VISIBLE);
		} else {
			int numberOfFilters = 0;

			// Count number of activated filters
			if (cbFUsed.isChecked())
				numberOfFilters++;
			if (cbFInternet.isChecked())
				numberOfFilters++;
			if (etFilter.getText().length() > 0)
				numberOfFilters++;
			if (cbFilter.isChecked())
				numberOfFilters++;
			if (cbFPermission.isChecked())
				numberOfFilters++;
			if (!mUserSystemOrBoth.equals(APP_FILTER_BOTH))
				numberOfFilters++;

			// Change text
			if (numberOfFilters == 0)
				tvFilters.setText(getResources().getString(R.string.title_nofilter));
			else
				tvFilters.setText(getResources().getQuantityString(R.plurals.title_filters, numberOfFilters,
						numberOfFilters));

			// Change visibility
			tvFilters.setVisibility(TextView.VISIBLE);
			etFilter.setVisibility(EditText.GONE);
			imgClear.setVisibility(ImageView.GONE);
			llFilters.setVisibility(LinearLayout.GONE);
		}

		imgFilterToggle.setImageDrawable(getResources().getDrawable(
				getThemed(mFiltersHidden ? R.attr.icon_expander_maximized : R.attr.icon_expander_minimized)));
		mFiltersHidden = !mFiltersHidden;
	}

	// Tasks

	private class UpdateTask extends AsyncTask<String, String, String> {
		@Override
		protected String doInBackground(String... uri) {
			return fetchJson(uri);
		}

		@Override
		protected void onPostExecute(String json) {
			super.onPostExecute(json);
			if (json != null)
				processJson(json);
		}

		private String fetchJson(String... uri) {
			try {
				// Request downloads
				int TIMEOUT_MILLISEC = 30000; // = 30 seconds
				HttpParams httpParams = new BasicHttpParams();
				HttpConnectionParams.setConnectionTimeout(httpParams, TIMEOUT_MILLISEC);
				HttpConnectionParams.setSoTimeout(httpParams, TIMEOUT_MILLISEC);
				HttpClient httpclient = new DefaultHttpClient(httpParams);
				HttpResponse response = httpclient.execute(new HttpGet(uri[0]));
				StatusLine statusLine = response.getStatusLine();

				if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
					// Succeeded
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					response.getEntity().writeTo(out);
					out.close();
					return out.toString("ISO-8859-1");
				} else {
					// Failed
					response.getEntity().getContent().close();
					throw new IOException(statusLine.getReasonPhrase());
				}
			} catch (Throwable ex) {
				Util.bug(null, ex);
				return ex.toString();
			}
		}

		private void processJson(String json) {
			try {
				// Parse result
				String version = null;
				String url = null;
				if (json != null)
					if (json.startsWith("{")) {
						long newest = 0;
						String prefix = "XPrivacy_";
						JSONObject jRoot = new JSONObject(json);
						JSONArray jArray = jRoot.getJSONArray("list");
						for (int i = 0; jArray != null && i < jArray.length(); i++) {
							// File
							JSONObject jEntry = jArray.getJSONObject(i);
							String filename = jEntry.getString("filename");
							if (filename.startsWith(prefix)) {
								// Check if newer
								long modified = jEntry.getLong("modified");
								if (modified > newest) {
									newest = modified;
									version = filename.substring(prefix.length()).replace(".apk", "");
									url = "http://goo.im" + jEntry.getString("path");
								}
							}
						}
					} else {
						Toast toast = Toast.makeText(ActivityMain.this, json, Toast.LENGTH_LONG);
						toast.show();
					}

				if (url == null || version == null) {
					// Assume no update
					String msg = getString(R.string.msg_noupdate);
					Toast toast = Toast.makeText(ActivityMain.this, msg, Toast.LENGTH_LONG);
					toast.show();
				} else {
					// Compare versions
					PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
					Version ourVersion = new Version(pInfo.versionName);
					Version latestVersion = new Version(version);
					if (ourVersion.compareTo(latestVersion) < 0) {
						// Update available
						Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
						startActivity(browserIntent);
					} else {
						// No update available
						String msg = getString(R.string.msg_noupdate);
						Toast toast = Toast.makeText(ActivityMain.this, msg, Toast.LENGTH_LONG);
						toast.show();
					}
				}
			} catch (Throwable ex) {
				Toast toast = Toast.makeText(ActivityMain.this, ex.toString(), Toast.LENGTH_LONG);
				toast.show();
				Util.bug(null, ex);
			}
		}
	}

	private class AppListTask extends AsyncTask<Object, Integer, List<ApplicationInfoEx>> {
		private String mRestrictionName;
		private ProgressDialog mProgressDialog;

		@Override
		protected List<ApplicationInfoEx> doInBackground(Object... params) {
			mRestrictionName = null;

			// Delegate
			return ApplicationInfoEx.getXApplicationList(ActivityMain.this, mProgressDialog);
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();

			// Reset spinner
			spRestriction.setEnabled(false);

			// Reset filters
			EditText etFilter = (EditText) findViewById(R.id.etFilter);
			etFilter.setEnabled(false);

			CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
			cbFilter.setEnabled(false);

			// Show progress dialog
			ListView lvApp = (ListView) findViewById(R.id.lvApp);
			mProgressDialog = new ProgressDialog(lvApp.getContext());
			mProgressDialog.setMessage(getString(R.string.msg_loading));
			mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mProgressDialog.setCancelable(false);
			mProgressDialog.show();
		}

		@Override
		protected void onPostExecute(List<ApplicationInfoEx> listApp) {
			super.onPostExecute(listApp);

			// Display app list
			mAppAdapter = new AppListAdapter(ActivityMain.this, R.layout.mainentry, listApp, mRestrictionName);
			ListView lvApp = (ListView) findViewById(R.id.lvApp);
			lvApp.setAdapter(mAppAdapter);

			// Dismiss progress dialog
			try {
				mProgressDialog.dismiss();
			} catch (Throwable ex) {
				Util.bug(null, ex);
			}

			// Enable filters
			EditText etFilter = (EditText) findViewById(R.id.etFilter);
			etFilter.setEnabled(true);

			CheckBox cbFilter = (CheckBox) findViewById(R.id.cbFilter);
			cbFilter.setEnabled(true);

			// Enable spinner
			Spinner spRestriction = (Spinner) findViewById(R.id.spRestriction);
			spRestriction.setEnabled(true);

			// Restore state
			ActivityMain.this.selectRestriction(spRestriction.getSelectedItemPosition());
		}
	}

	// Adapters

	private class SpinnerAdapter extends ArrayAdapter<String> {
		public SpinnerAdapter(Context context, int textViewResourceId) {
			super(context, textViewResourceId);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = super.getView(position, convertView, parent);
			row.setBackgroundColor(getBackgroundColor(position));
			return row;
		}

		@Override
		public View getDropDownView(int position, View convertView, ViewGroup parent) {
			View row = super.getDropDownView(position, convertView, parent);
			row.setBackgroundColor(getBackgroundColor(position));
			return row;
		}

		private int getBackgroundColor(int position) {
			String restrictionName = (position == 0 ? null : PrivacyManager.getRestrictions(true).get(position - 1));
			if (PrivacyManager.isDangerousRestriction(restrictionName))
				return getResources().getColor(getThemed(R.attr.color_dangerous));
			else
				return Color.TRANSPARENT;
		}
	}

	@SuppressLint("DefaultLocale")
	private class AppListAdapter extends ArrayAdapter<ApplicationInfoEx> {
		private Context mContext;
		private List<ApplicationInfoEx> mListAppAll;
		private List<ApplicationInfoEx> mListAppSelected;
		private String mRestrictionName;
		private LayoutInflater mInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

		public AppListAdapter(Context context, int resource, List<ApplicationInfoEx> objects,
				String initialRestrictionName) {
			super(context, resource, objects);
			mContext = context;
			mListAppAll = new ArrayList<ApplicationInfoEx>();
			mListAppAll.addAll(objects);
			mRestrictionName = initialRestrictionName;
			selectApps();
		}

		public void setRestrictionName(String restrictionName) {
			mRestrictionName = restrictionName;
			selectApps();
			notifyDataSetChanged();
		}

		public String getRestrictionName() {
			return mRestrictionName;
		}

		private void selectApps() {
			mListAppSelected = new ArrayList<ApplicationInfoEx>();
			if (PrivacyManager.getSettingBool(null, ActivityMain.this, 0, PrivacyManager.cSettingFPermission, true,
					false)) {
				for (ApplicationInfoEx appInfo : mListAppAll)
					if (mRestrictionName == null)
						mListAppSelected.add(appInfo);
					else if (PrivacyManager.hasPermission(mContext, appInfo.getPackageName(), mRestrictionName)
							|| PrivacyManager.getUsed(mContext, appInfo.getUid(), mRestrictionName, null) > 0)
						mListAppSelected.add(appInfo);
			} else
				mListAppSelected.addAll(mListAppAll);
		}

		@Override
		public Filter getFilter() {
			return new AppFilter();
		}

		private class AppFilter extends Filter {
			public AppFilter() {
			}

			@Override
			protected FilterResults performFiltering(CharSequence constraint) {
				FilterResults results = new FilterResults();

				// Get arguments
				String[] components = ((String) constraint).split("\\n");
				boolean fUsed = Boolean.parseBoolean(components[0]);
				boolean fInternet = Boolean.parseBoolean(components[1]);
				String fName = components[2];
				boolean fRestricted = Boolean.parseBoolean(components[3]);
				String fUserSystemBoth = components[4];

				// Match applications
				List<ApplicationInfoEx> lstApp = new ArrayList<ApplicationInfoEx>();
				for (ApplicationInfoEx xAppInfo : AppListAdapter.this.mListAppSelected) {
					// Get if used
					boolean used = false;
					if (fUsed)
						used = (PrivacyManager.getUsed(getApplicationContext(), xAppInfo.getUid(), mRestrictionName,
								null) != 0);

					// Get if internet
					boolean internet = false;
					if (fInternet)
						internet = xAppInfo.hasInternet();

					// Get if name contains
					boolean contains = false;
					if (!fName.equals(""))
						contains = (xAppInfo.toString().toLowerCase().contains(((String) fName).toLowerCase()));

					// Get some restricted
					boolean someRestricted = false;
					if (fRestricted)
						if (mRestrictionName == null) {
							for (boolean restricted : PrivacyManager.getRestricted(getApplicationContext(),
									xAppInfo.getUid(), false))
								if (restricted) {
									someRestricted = true;
									break;
								}
						} else
							someRestricted = PrivacyManager.getRestricted(null, getApplicationContext(),
									xAppInfo.getUid(), mRestrictionName, null, false, false);

					// Get if system or not
					boolean userSystemBoth = false;
					if (fUserSystemBoth.equals(APP_FILTER_SYS))
						userSystemBoth = xAppInfo.getIsSystem();
					else if (fUserSystemBoth.equals(APP_FILTER_USER))
						userSystemBoth = !xAppInfo.getIsSystem();

					// Match application
					if ((fUsed ? used : true) && (fInternet ? internet : true) && (fName.equals("") ? true : contains)
							&& (fRestricted ? someRestricted : true)
							&& (fUserSystemBoth.equals(APP_FILTER_BOTH) ? true : userSystemBoth))
						lstApp.add(xAppInfo);
				}

				synchronized (this) {
					results.values = lstApp;
					results.count = lstApp.size();
				}

				return results;
			}

			@Override
			@SuppressWarnings("unchecked")
			protected void publishResults(CharSequence constraint, FilterResults results) {
				clear();
				TextView tvStats = (TextView) findViewById(R.id.tvStats);
				ProgressBar pbFilter = (ProgressBar) findViewById(R.id.pbFilter);
				pbFilter.setVisibility(ProgressBar.GONE);
				tvStats.setVisibility(TextView.VISIBLE);
				tvStats.setText(results.count + "/" + AppListAdapter.this.mListAppSelected.size());
				if (results.values == null)
					notifyDataSetInvalidated();
				else {
					addAll((ArrayList<ApplicationInfoEx>) results.values);
					notifyDataSetChanged();
				}
			}
		}

		private class ViewHolder {
			private View row;
			private int position;
			public ImageView imgIcon;
			public ImageView imgUsed;
			public ImageView imgGranted;
			public ImageView imgInternet;
			public ImageView imgFrozen;
			public TextView tvName;
			public ImageView imgCBName;
			public RelativeLayout rlName;

			public ViewHolder(View theRow, int thePosition) {
				row = theRow;
				position = thePosition;
				imgIcon = (ImageView) row.findViewById(R.id.imgIcon);
				imgUsed = (ImageView) row.findViewById(R.id.imgUsed);
				imgGranted = (ImageView) row.findViewById(R.id.imgGranted);
				imgInternet = (ImageView) row.findViewById(R.id.imgInternet);
				imgFrozen = (ImageView) row.findViewById(R.id.imgFrozen);
				tvName = (TextView) row.findViewById(R.id.tvName);
				imgCBName = (ImageView) row.findViewById(R.id.imgCBName);
				rlName = (RelativeLayout) row.findViewById(R.id.rlName);
			}
		}

		private class HolderTask extends AsyncTask<Object, Object, Object> {
			private int position;
			private ViewHolder holder;
			private ApplicationInfoEx xAppInfo = null;
			private boolean used;
			private boolean granted = true;
			private List<String> listRestriction;
			private boolean allRestricted = true;
			private boolean someRestricted = false;

			public HolderTask(int thePosition, ViewHolder theHolder, ApplicationInfoEx theAppInfo) {
				position = thePosition;
				holder = theHolder;
				xAppInfo = theAppInfo;
			}

			@Override
			protected Object doInBackground(Object... params) {
				if (holder.position == position) {
					// Get if used
					used = (PrivacyManager.getUsed(holder.row.getContext(), xAppInfo.getUid(), mRestrictionName, null) != 0);

					// Get if granted
					if (mRestrictionName != null)
						if (!PrivacyManager.hasPermission(holder.row.getContext(), xAppInfo.getPackageName(),
								mRestrictionName))
							granted = false;

					// Get restrictions
					if (mRestrictionName == null)
						listRestriction = PrivacyManager.getRestrictions(false);
					else {
						listRestriction = new ArrayList<String>();
						listRestriction.add(mRestrictionName);
					}

					// Get all/some restricted
					if (mRestrictionName == null)
						for (boolean restricted : PrivacyManager.getRestricted(holder.row.getContext(),
								xAppInfo.getUid(), true)) {
							allRestricted = allRestricted && restricted;
							someRestricted = someRestricted || restricted;
						}
					else {
						boolean restricted = PrivacyManager.getRestricted(null, holder.row.getContext(),
								xAppInfo.getUid(), mRestrictionName, null, false, false);
						allRestricted = restricted;
						someRestricted = restricted;
					}
				}
				return null;
			}

			@Override
			protected void onPostExecute(Object result) {
				if (holder.position == position && xAppInfo != null) {
					// Check if used
					holder.tvName.setTypeface(null, used ? Typeface.BOLD_ITALIC : Typeface.NORMAL);
					holder.imgUsed.setVisibility(used ? View.VISIBLE : View.INVISIBLE);

					// Check if permission
					holder.imgGranted.setVisibility(granted ? View.VISIBLE : View.INVISIBLE);

					// Check if internet access
					holder.imgInternet.setVisibility(xAppInfo.hasInternet() ? View.VISIBLE : View.INVISIBLE);

					// Check if frozen
					holder.imgFrozen.setVisibility(xAppInfo.isFrozen() ? View.VISIBLE : View.INVISIBLE);

					// Display restriction
					holder.imgCBName.setEnabled(mRestrictionName == null && someRestricted ? allRestricted : true);
					holder.imgCBName.setImageResource(allRestricted ? R.drawable.checkbox_check
							: (someRestricted ? (mRestrictionName == null ? R.drawable.checkbox_half
									: R.drawable.checkbox_check) : android.R.color.transparent));

					// Listen for restriction changes
					holder.rlName.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View view) {
							if (!holder.imgCBName.isEnabled())
								return;

							// Get all/some restricted
							boolean allRestricted = true;
							if (mRestrictionName == null)
								for (boolean restricted : PrivacyManager.getRestricted(view.getContext(),
										xAppInfo.getUid(), false))
									allRestricted = allRestricted && restricted;
							else {
								boolean restricted = PrivacyManager.getRestricted(null, view.getContext(),
										xAppInfo.getUid(), mRestrictionName, null, false, false);
								allRestricted = restricted;
							}

							// Process click
							allRestricted = !allRestricted;
							for (String restrictionName : listRestriction)
								PrivacyManager.setRestricted(null, view.getContext(), xAppInfo.getUid(),
										restrictionName, null, allRestricted);
							holder.imgCBName.setEnabled(!(allRestricted && mRestrictionName == null));
							holder.imgCBName
									.setImageResource(allRestricted ? (mRestrictionName == null ? R.drawable.checkbox_half
											: R.drawable.checkbox_check)
											: android.R.color.transparent);
						}
					});
				}
			}
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			ViewHolder holder;
			if (convertView == null) {
				convertView = mInflater.inflate(R.layout.mainentry, null);
				holder = new ViewHolder(convertView, position);
				convertView.setTag(holder);
			} else {
				holder = (ViewHolder) convertView.getTag();
				holder.position = position;
			}

			// Get info
			final ApplicationInfoEx xAppInfo = getItem(holder.position);

			// Set background color
			if (xAppInfo.getIsSystem())
				holder.row.setBackgroundColor(getResources().getColor(getThemed(R.attr.color_dangerous)));
			else
				holder.row.setBackgroundColor(Color.TRANSPARENT);

			// Set icon
			holder.imgIcon.setImageDrawable(xAppInfo.getIcon());
			holder.imgIcon.setVisibility(View.VISIBLE);

			// Handle details click
			holder.imgIcon.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					Intent intentSettings = new Intent(view.getContext(), ActivityApp.class);
					intentSettings.putExtra(ActivityApp.cPackageName, xAppInfo.getPackageName());
					intentSettings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					view.getContext().startActivity(intentSettings);
				}
			});

			// Set data
			holder.tvName.setText(xAppInfo.toString());
			holder.tvName.setTypeface(null, Typeface.NORMAL);
			holder.imgUsed.setVisibility(View.INVISIBLE);
			holder.imgGranted.setVisibility(View.INVISIBLE);
			holder.imgInternet.setVisibility(View.INVISIBLE);
			holder.imgFrozen.setVisibility(View.INVISIBLE);
			holder.imgCBName.setImageResource(android.R.color.transparent);
			holder.imgCBName.setEnabled(false);

			// Async update
			new HolderTask(position, holder, xAppInfo).executeOnExecutor(mExecutor, (Object) null);

			return convertView;
		}
	}

	// Helper methods

	private void checkLicense() {
		if (Util.hasProLicense(this) == null) {
			if (Util.isProInstalled(this))
				try {
					int uid = getPackageManager().getApplicationInfo("biz.bokhorst.xprivacy.pro", 0).uid;
					PrivacyManager.deleteRestrictions(this, uid);
					Util.log(null, Log.INFO, "Licensing: check");
					startActivityForResult(new Intent("biz.bokhorst.xprivacy.pro.CHECK"), ACTIVITY_LICENSE);
				} catch (Throwable ex) {
					Util.bug(null, ex);
				}
		} else {
			Toast toast = Toast.makeText(this, getString(R.string.menu_pro), Toast.LENGTH_LONG);
			toast.show();
		}
	}

	private int getThemed(int attr) {
		TypedValue typedvalueattr = new TypedValue();
		getTheme().resolveAttribute(attr, typedvalueattr, true);
		return typedvalueattr.resourceId;
	}
}
