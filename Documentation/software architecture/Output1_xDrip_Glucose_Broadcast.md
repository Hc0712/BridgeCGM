# Integration with xDrip+ via Broadcast Intent
Suitable sender type: Normal broadcast (sendBroadcast) with explicit targeting to xDrip via intent.setPackage("com.eveningoutpost.dexdrip"). That is the exact pattern shown in xDrip’s documentation and test for the NS_EMULATOR integration. [stackoverflow.com], [xDrip-2025...05.zip.doc | Word]
What xDrip is using now to receive: a manifest-declared BroadcastReceiver (.NSEmulatorReceiver) listening for com.eveningoutpost.dexdrip.NS_EMULATOR, i.e. a cross-app custom broadcast receiver, not a local broadcast receiver. The example sender path uses sendBroadcast(), not sendOrderedBroadcast(). [stackoverflow.com], [xDrip-2025...05.zip.doc | Word]
Not suitable: Local broadcast is not appropriate for a third-party sender app talking to xDrip, because LocalBroadcastManager is for within-app / same-process use and has been deprecated. [developer....ndroid.com]
Usually not useful here: Ordered broadcast is technically possible in Android, but xDrip’s NS_EMULATOR path does not implement ordered-broadcast result handling or ACK/status return, so ordered delivery gives you little practical benefit. [developer....ndroid.com], [bing.com]


## Overview
This documentation provides guidance on how to send a broadcast intent from a third-party Android application to insert a glucose sensor record into the xDrip application. There are a few different broadcast receivers but this documents the Nightscout Emulation receiver just for glucose records.

## Sending a Broadcast Intent
To insert a glucose sensor record into xDrip, construct and send a broadcast intent with specific parameters as described below.

### Intent Action
Use the following intent action when constructing the intent:
```java
Intent intent = new Intent("com.eveningoutpost.dexdrip.NS_EMULATOR");
```

### Intent Package
To ensure the intent is received only by xDrip and to be allowed on Android 8+, specify the package name:
```java
intent.setPackage("com.eveningoutpost.dexdrip");
```

### Extra Parameters
Add extra parameters to the intent for the data collection and JSON payload containing the sensor readings.

- Collection: Indicate the type of data collection using the key `"collection"` with the value `"entries"`.
- Data: Provide the glucose sensor readings in a JSON array format with the key `"data"`.

### JSON Payload Structure
Construct the JSON payload with the glucose sensor readings as follows:
- Each reading should be a JSON object with the following attributes:
    - `"type"`: Set to `"sgv"` for sensor glucose value record.
    - `"date"`: The timestamp of the reading in milliseconds since epoch.
    - `"sgv"`: The glucose value in mg/dL.
    - `"direction"`: The rate of change of the glucose value, represented by a string such as `"SingleUp"`. Refer to `BgReading.slopeFromName()` for possible values.

Here is an example JSON payload with a single reading:
```java
final JSONArray sgv_array = new JSONArray();
final JSONObject sgv_object = new JSONObject();
sgv_object.put("type", "sgv");
sgv_object.put("date", 1526817691000.0);
sgv_object.put("sgv", 158);
sgv_object.put("direction", "SingleUp");
sgv_array.put(sgv_object);
intent.putExtra("data", sgv_array.toString());
```

A code example of this is in the unit test source tree in `NSEmulatorReceiverTest.bgReadingExampleBroadcast()`

### Sending the Intent
After constructing the intent, use `sendBroadcast()` to send it:
```java
context.sendBroadcast(intent);
```

## Testing the Integration
For testing purposes, make sure you set xDrip `Hardware Data Source` to `640G / Eversense` to enable the receiver.
