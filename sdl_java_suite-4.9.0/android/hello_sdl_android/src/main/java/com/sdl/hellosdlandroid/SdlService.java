package com.sdl.hellosdlandroid;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.screen.SoftButtonObject;
import com.smartdevicelink.managers.screen.SoftButtonState;
import com.smartdevicelink.managers.screen.choiceset.ChoiceCell;
import com.smartdevicelink.managers.screen.choiceset.ChoiceSet;
import com.smartdevicelink.managers.screen.choiceset.ChoiceSetSelectionListener;
import com.smartdevicelink.managers.screen.menu.MenuCell;
import com.smartdevicelink.managers.screen.menu.MenuSelectionListener;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.TTSChunkFactory;
import com.smartdevicelink.proxy.rpc.Alert;
import com.smartdevicelink.proxy.rpc.OnButtonEvent;
import com.smartdevicelink.proxy.rpc.OnButtonPress;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnVehicleData;
import com.smartdevicelink.proxy.rpc.SendLocation;
import com.smartdevicelink.proxy.rpc.SetDisplayLayout;
import com.smartdevicelink.proxy.rpc.SetDisplayLayoutResponse;
import com.smartdevicelink.proxy.rpc.SoftButton;
import com.smartdevicelink.proxy.rpc.Speak;
import com.smartdevicelink.proxy.rpc.SubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.UnsubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.InteractionMode;
import com.smartdevicelink.proxy.rpc.enums.PredefinedLayout;
import com.smartdevicelink.proxy.rpc.enums.SoftButtonType;
import com.smartdevicelink.proxy.rpc.enums.TriggerSource;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;
import com.smartdevicelink.util.DebugTool;

//import com.android.volley.Request;
//import com.android.volley.RequestQueue;
//import com.android.volley.Response;
//import com.android.volley.VolleyError;
//import com.android.volley.toolbox.JsonObjectRequest;
//import com.google.android.libraries.places.api.Places;
//import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.lang.Thread;

public class SdlService extends Service {

	private static final String TAG 					= "SDL Service";

	private static final String APP_NAME 				= "Smart Recommendations";
	private static final String APP_ID 					= "8678309";

	private static final String ICON_FILENAME 			= "hello_sdl_icon.png";
	private static final String SDL_IMAGE_FILENAME  	= "sdl_full_image.png";

	private static final String WELCOME_SHOW 			= "This application is listening to current changes in your vehicle metrics!";
	private static final String WELCOME_SPEAK 			= "Welcome to Hello S D L";

	private static final String TEST_COMMAND_NAME 		= "Test Command";

	private static final int FOREGROUND_SERVICE_ID = 111;

	// TCP/IP transport config
	// The default port is 12345
	// The IP is of the machine that is running SDL Core
	private static final int TCP_PORT = 12345;
	private static final String DEV_MACHINE_IP_ADDRESS = "10.142.133.116";
//	final String api_key = "AIzaSyDn2gjqb-WW-MgSXoWKCY1k1WzkJUjLNOI";
//	private PlacesClient pc;

	// variable to create and call functions of the SyncProxy
	private SdlManager sdlManager = null;
	private List<ChoiceCell> choiceCellList;

	private static final double fuelTankSize = 12.4; // 2018 Ford Focus Base Model
	private static final int mpg = 24; // 2018 Ford Focus Base Model city mpg - naive method
	private double fuelLevel = 9999999;	// Percentage of fuel left in tank - naive method
	private double latitude;
	private double longitude;
	private double milesRemaining;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");
		super.onCreate();

//		if (!Places.isInitialized()) {
//			Places.initialize(getApplicationContext(), api_key);
//		}
//		// Create a new Places client instance
//		pc = Places.createClient(this);
//		// Get a request queue for our searches
//		RequestQueue queue = Singleton.getInstance(this.getApplicationContext()).getRequestQueue();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			enterForeground();
		}
	}

	// Helper method to let the service enter foreground mode
	@SuppressLint("NewApi")
	public void enterForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				Notification serviceNotification = new Notification.Builder(this, channel.getId())
						.setContentTitle("Connected through SDL")
						.setSmallIcon(R.drawable.ic_sdl)
						.build();
				startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startProxy();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			stopForeground(true);
		}

		if (sdlManager != null) {
			sdlManager.dispose();
		}

		super.onDestroy();
	}

	private void startProxy() {
		// This logic is to select the correct transport and security levels defined in the selected build flavor
		// Build flavors are selected by the "build variants" tab typically located in the bottom left of Android Studio
		// Typically in your app, you will only set one of these.
		if (sdlManager == null) {
			Log.i(TAG, "Starting SDL Proxy");
			// Enable DebugTool for debug build type
			if (BuildConfig.DEBUG){
				DebugTool.enableDebugTool();
			}
			BaseTransportConfig transport = null;
			if (BuildConfig.TRANSPORT.equals("MULTI")) {
				int securityLevel;
				if (BuildConfig.SECURITY.equals("HIGH")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
				} else if (BuildConfig.SECURITY.equals("MED")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
				} else if (BuildConfig.SECURITY.equals("LOW")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
				} else {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
				}
				transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
			} else if (BuildConfig.TRANSPORT.equals("TCP")) {
				transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
			} else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
				MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
				mtc.setRequiresHighBandwidth(true);
				transport = mtc;
			}

			// The app type to be used
			Vector<AppHMIType> appType = new Vector<>();
			appType.add(AppHMIType.MEDIA);

			// The manager listener helps you know when certain events that pertain to the SDL Manager happen
			// Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
			SdlManagerListener listener = new SdlManagerListener() {
				@Override
				public void onStart() {
					// HMI Status Listener
					sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {
							OnHMIStatus status = (OnHMIStatus) notification;
							if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {
								sendMenus();
								performWelcomeSpeak();
								performWelcomeShow();
								preloadChoices();
								changeUITemplate(null,true);
								subscribeVehicleData();
								listenVehicleData();
							}
						}
					});
				}

				@Override
				public void onDestroy() {
					SdlService.this.stopSelf();
				}

				@Override
				public void onError(String info, Exception e) {
				}
			};

			// Create App Icon, this is set in the SdlManager builder
			SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);

			// The manager builder sets options for your session
			SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
			builder.setAppTypes(appType);
			builder.setTransportType(transport);
			builder.setAppIcon(appIcon);
			sdlManager = builder.build();
			sdlManager.start();
		}
	}

	private double naiveMileage() {
		// Get mileage using hardcoded mpg value
		return milesRemaining = mpg * (fuelLevel/100) * fuelTankSize;
	}

	/**
	 *  Add menus for the app on SDL.
	 */
	private void sendMenus(){

		// some arts
		SdlArtwork livio = new SdlArtwork("livio", FileType.GRAPHIC_PNG, R.drawable.sdl, false);

		// some voice commands
		List<String> voice2 = Collections.singletonList("Cell two");

		MenuCell mainCell1 = new MenuCell("Test Cell 1 (speak)", livio, null, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				Log.i(TAG, "Test cell 1 triggered. Source: "+ trigger.toString());
			}
		});

		MenuCell mainCell2 = new MenuCell("Test Cell 2", null, voice2, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				Log.i(TAG, "Test cell 2 triggered. Source: "+ trigger.toString());
			}
		});

		// SUB MENU

		MenuCell subCell1 = new MenuCell("SubCell 1",null, null, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				Log.i(TAG, "Sub cell 1 triggered. Source: "+ trigger.toString());
			}
		});

		MenuCell subCell2 = new MenuCell("SubCell 2",null, null, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				Log.i(TAG, "Sub cell 2 triggered. Source: "+ trigger.toString());
			}
		});

		// sub menu parent cell
		MenuCell mainCell3 = new MenuCell("Test Cell 3 (sub menu)", null, Arrays.asList(subCell1,subCell2));

		MenuCell mainCell4 = new MenuCell("Show Perform Interaction", null, null, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				showPerformInteraction();
			}
		});

		MenuCell mainCell5 = new MenuCell("Clear the menu",null, null, new MenuSelectionListener() {
			@Override
			public void onTriggered(TriggerSource trigger) {
				Log.i(TAG, "Clearing Menu. Source: "+ trigger.toString());
				// Clear this thing
				sdlManager.getScreenManager().setMenu(Collections.<MenuCell>emptyList());
			}
		});

		// Send the entire menu off to be created
		sdlManager.getScreenManager().setMenu(Arrays.asList(mainCell1, mainCell2, mainCell3, mainCell4, mainCell5));
	}

	/**
	 * Will speak a sample welcome message
	 */
	private void performWelcomeSpeak(){
		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks(WELCOME_SPEAK)));
	}

	/**
	 * Use the Screen Manager to set the initial screen text and set the image.
	 * Because we are setting multiple items, we will call beginTransaction() first,
	 * and finish with commit() when we are done.
	 */
	private void performWelcomeShow() {
		sdlManager.getScreenManager().beginTransaction();
		sdlManager.getScreenManager().setTextField1(APP_NAME);
		sdlManager.getScreenManager().setTextField2(WELCOME_SHOW);
		sdlManager.getScreenManager().setPrimaryGraphic(new SdlArtwork(SDL_IMAGE_FILENAME, FileType.GRAPHIC_PNG, R.drawable.sdl, true));
		sdlManager.getScreenManager().commit(new CompletionListener() {
			@Override
			public void onComplete(boolean success) {
				if (success){
					Log.i(TAG, "welcome show successful");
				}
			}
		});
	}

	// Choice Set

	private void preloadChoices(){
		ChoiceCell cell1 = new ChoiceCell("Item 1");
		ChoiceCell cell2 = new ChoiceCell("Item 2");
		ChoiceCell cell3 = new ChoiceCell("Item 3");
		choiceCellList = new ArrayList<>(Arrays.asList(cell1,cell2,cell3));
		sdlManager.getScreenManager().preloadChoices(choiceCellList, null);
	}

	private void showPerformInteraction(){
		if (choiceCellList != null) {
			ChoiceSet choiceSet = new ChoiceSet("Choose an Item from the list", choiceCellList, new ChoiceSetSelectionListener() {
				@Override
				public void onChoiceSelected(ChoiceCell choiceCell, TriggerSource triggerSource, int rowIndex) {
				}

				@Override
				public void onError(String error) {
					Log.e(TAG, "There was an error showing the perform interaction: "+ error);
				}
			});
			sdlManager.getScreenManager().presentChoiceSet(choiceSet, InteractionMode.MANUAL_ONLY);
		}
	}

	private void sendLocation(double longitude, double latitude) {
		SendLocation request = new SendLocation();
		request.setLatitudeDegrees(latitude);
		request.setLongitudeDegrees(longitude);

		request.setOnRPCResponseListener((new OnRPCResponseListener() {
			@Override
			public void onResponse(int correlationId, RPCResponse response) {
				if(response.getSuccess()) {
					Log.i("SdlService", "Successful location setting!");
				} else {
					Log.i("SdlService", "Request to set location failed!");
				}
			}
		}));
		sdlManager.sendRPC(request);
	}
//


	private void subscribeVehicleData() {
		SubscribeVehicleData request = new SubscribeVehicleData();
		request.setFuelLevel(true);
		request.setGps(true);
		request.setSpeed(true);
		request.setInstantFuelConsumption(true);
		request.setOnRPCResponseListener((new OnRPCResponseListener() {
			@Override
			public void onResponse(int correlationId, RPCResponse response) {
				if(response.getSuccess()) {
					Log.i("SdlService", "Successful vehicle data subscription!");
				} else {
					Log.i("SdlService", "Request to vehicle data failed!");
				}
			}
		}));
		sdlManager.sendRPC(request);
	}

	private void listenVehicleData() {
		sdlManager.addOnRPCNotificationListener(FunctionID.ON_VEHICLE_DATA, new OnRPCNotificationListener() {
			@Override
			public void onNotified(RPCNotification notification) {
				OnVehicleData dataNotification = (OnVehicleData) notification;
				Log.i("SdlService", "We are listening!");

				if (dataNotification.getFuelLevel() != null) {
					fuelLevel = dataNotification.getFuelLevel();
					Log.i("SdlService", "Listening to Fuel Level: " + fuelLevel);
					milesRemaining = naiveMileage();
					Log.i("SdlService", "Miles Remaining: " + milesRemaining);
				}

				if (dataNotification.getGps() != null) {
					latitude = dataNotification.getGps().getLatitudeDegrees();
					longitude = dataNotification.getGps().getLongitudeDegrees();
				}

				if (fuelLevel <= 5) {
					makeAlert(1, longitude, latitude);
					Log.i("SdlService", "You only have 5% gas left!");
				} else if (fuelLevel <= 10){
					makeAlert(2, longitude, latitude);
					Log.i("SdlService", "You only have 10% gas left!");
				} else if (fuelLevel <= 25){
					makeAlert(3, longitude, latitude);
					Log.i("SdlService", "You only have 25% gas left!");
				} else {
					Log.i("SdlService", "Fuel Level: " + dataNotification.getFuelLevel());
				}
			}
		});
	}

	private void changeUITemplate(final JsonArray inputArray, final boolean start) {
		if (start) {
			SetDisplayLayout request = new SetDisplayLayout();
			request.setDisplayLayout(PredefinedLayout.GRAPHIC_WITH_TILES.toString());
			request.setOnRPCResponseListener(new OnRPCResponseListener() {
				@Override
				public void onResponse(int correlationId, RPCResponse response) {
					if(((SetDisplayLayoutResponse) response).getSuccess()){
						Log.i("SdlService", "Layout set successfully!");
					}else{
						Log.i("SdlService", "Failed to set layout!");
					}
				}
			});

			sdlManager.sendRPC(request);
		}


		if (!start) {
			List<SoftButtonObject> buttonList = new ArrayList<>();
			for (int i = 0; i < inputArray.size(); i++) {
				final JsonObject thisObject = inputArray.get(i).getAsJsonObject();
				SoftButtonState textState = new SoftButtonState("Object#"+i, thisObject.get("name").getAsString(), null);
				SoftButtonObject softButtonObject = new SoftButtonObject("softButtonObject#"+i, Collections.singletonList(textState), textState.getName(), new SoftButtonObject.OnEventListener() {
					@Override
					public void onPress(SoftButtonObject softButtonObject, OnButtonPress onButtonPress) {
						Log.i("SdlService","I have been pressed!");
						sendLocation(thisObject.get("longitude").getAsDouble(), thisObject.get("latitude").getAsDouble());
					}

					@Override
					public void onEvent(SoftButtonObject softButtonObject, OnButtonEvent onButtonEvent) {

					}
				});
				buttonList.add(softButtonObject);
			}
			sdlManager.getScreenManager().setSoftButtonObjects(buttonList);
		}
	}

	private void makeAlert(final int i, final double longitude, final double latitude){
		Log.i("SdlService", "Making an alert!");
		Alert alert = new Alert();
		alert.setAlertText1("Oh no! You are about to run out of gas!");
		List<SoftButton> buttonList = new ArrayList<>();

// Soft buttons
		final int OK_button = 123; // Set it to any unique ID
		final SoftButton okButton = new SoftButton(SoftButtonType.SBT_TEXT, OK_button);
		okButton.setText("Show Locations");
		buttonList.add(okButton);

		final int dismissal = 456;
		final SoftButton dismissButton = new SoftButton(SoftButtonType.SBT_TEXT, dismissal);
		dismissButton.setText("Dismiss");
		buttonList.add(dismissButton);

// Set the softbuttons(s) to the alert

		alert.setSoftButtons(buttonList);

// This listener is only needed once, and will work for all of soft buttons you send with your alert
		sdlManager.addOnRPCNotificationListener(FunctionID.ON_BUTTON_PRESS, new OnRPCNotificationListener() {
			@Override
			public void onNotified(RPCNotification notification) {
				Log.i("SdlService", "We made a choice!");
				OnButtonPress onButtonPress = (OnButtonPress) notification;
				if (onButtonPress.getCustomButtonName() == OK_button){
					Log.i("SdlService", "Ok button pressed");
					JsonArray results = searchNearestPlaces(longitude, latitude);
					changeUITemplate(results, false);
// 					also pass in milesRemaining
				} else if (onButtonPress.getCustomButtonName() == dismissal) {
					Log.i("SdlService", "Dismissal button pressed");
					try {
						Thread.sleep(1000*60*i);
					} catch (InterruptedException e) {
						Log.e("SdlService", "Sleep interrupted!");
					}
				}
			}
		});
		sdlManager.sendRPC(alert);
		Log.i("SdlService", "Alert sent!");
	}

	private void unSubscribeVehicleData() {
		UnsubscribeVehicleData unsubscribeRequest = new UnsubscribeVehicleData();
		unsubscribeRequest.setFuelLevel(true);
		unsubscribeRequest.setGps(true);
		unsubscribeRequest.setOnRPCResponseListener((new OnRPCResponseListener() {
			@Override
			public void onResponse(int correlationId, RPCResponse response) {
				if (response.getSuccess()) {
					Log.i("SdlService", "Vehicle data successfully unsubscribed!");
				} else {
					Log.i("SdlService", "Request to vehicle data failed!");
				}
			}
		}));
		sdlManager.sendRPC(unsubscribeRequest);
	}

//	private void fetchReq(String url) {
//		JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(Request.Method.GET, url,
//				null, new Response.Listener<JSONObject>() {
//			@Override
//			public void onResponse(JSONObject response) {
//				Log.d("Places Search", "Response=" + response.toString());
//				changeUITemplate(response, false);
//			}
//		},new Response.ErrorListener() {
//			@Override
//			public void onErrorResponse(VolleyError error) {
//				Log.e("Places Search", "Response: Error!");
//				}
//		});
//		Singleton.getInstance(this).addToRequestQueue(jsonObjectRequest);
//	}

	private JsonArray searchNearestPlaces(double latitude, double longitude) {
		JsonArray objectArray = new JsonArray();

		JsonObject thisObject = new JsonObject();
		thisObject.addProperty("name", "Chevron");
		thisObject.addProperty("longitude", 42.3223);
		thisObject.addProperty("latitude", 83.1763);
		objectArray.add(thisObject);

		JsonObject anotherObject = new JsonObject();
		anotherObject.addProperty("name", "Shells");
		anotherObject.addProperty("longitude", 41.6632);
		anotherObject.addProperty("latitude", 87.5609);
		objectArray.add(anotherObject);

		JsonObject finalObject = new JsonObject();
		finalObject.addProperty("name", "Shells");
		finalObject.addProperty("longitude", 38.1548);
		finalObject.addProperty("latitude", 85.7245);
		objectArray.add(finalObject);

		return objectArray;
//		String place = "gas_station";
//
//		StringBuilder url = new StringBuilder("https://maps.googleapis.com/maps/api/place/nearbysearch/json?");
//		url.append("location="+latitude+","+longitude);
//		url.append("&type="+place);
//		url.append("&rankby=distance");
//		url.append("&opennow=true");
//		url.append("&key="+api_key);
//
//		fetchReq(url.toString());
	}
}
