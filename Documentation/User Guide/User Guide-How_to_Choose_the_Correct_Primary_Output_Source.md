# How to Choose the Correct Primary Output Source

## Why this guide matters

This app can receive glucose data from more than one source.

Examples:

- different CGM vendors
- different transport types such as Notification, Bluetooth, Broadcast, or Network
- more than one source carrying similar glucose readings

To keep the app simple and reliable, you must choose **one exact Primary Output Source**.

That chosen source is the one the app uses for:

- the mini graph
- calibration
- alarms

All other sources can still be stored and shown on the main graph for comparison.

---

## 1. What is the Primary Output Source?

The **Primary Output Source** is one exact data source channel that you choose.

Once selected, that source becomes the only one used for:

- **mini graph display**
- **calibration** (if calibration is enabled)
- **alarm checking**

All other sources still remain available as reference data on the main graph.

---

## 2. What happens after you choose a Primary Output Source?

### The selected primary source
The selected source will:

- appear on the mini graph
- be calibrated if calibration is enabled
- be used by the app's alarm system

### The other sources
Other sources will:

- still be received if available
- still be stored
- still be shown on the main graph as raw data
- not be used for alarms
- not be calibrated
- not be shown on the mini graph

---

## 3. How the graphs work

## Main graph
The main graph is for comparison.

It shows:

- all raw data sources from different vendors and transport types
- the raw data of the selected primary source
- the calibrated data of the selected primary source if calibration is enabled

This helps you compare:

- different incoming sources
- the effect of calibration on the chosen primary source

## Mini graph
The mini graph is for focused viewing.

It shows only the selected Primary Output Source.

- if calibration is enabled, the mini graph shows calibrated primary output
- if calibration is disabled, the mini graph shows raw primary output

---

## 4. Why the app does not choose automatically for you

Two sources that look similar are not always the same thing.

For example:

- `Aidex / Notification`
- `Aidex / Bluetooth`

These may be:

- the same physical CGM using two different transfer paths
- or two different physical CGMs from the same vendor

Also, the same physical CGM may be able to send data through more than one transport type.

Because of this, the app does **not** try to guess which source is physically the same CGM.

Only you can decide which source should be treated as the Primary Output Source.

---

## 5. How to choose the correct Primary Output Source

Choose the source that you want the app to trust for:

- calibration
- alarms
- mini graph display

### A good source usually has these qualities

- it updates consistently
- it disconnects less often
- it matches the CGM data you want to monitor
- it is the source you want alarms to follow

### A practical rule
If several sources are available, choose the one that is:

- most stable
- most reliable in your daily usage
- easiest for you to trust as the app's main monitored source

---

## 6. Important warning about similar sources

Do not assume that two sources are identical just because they share the same vendor name.

Examples:

- two Aidex sources may belong to two different physical CGMs
- one Aidex Notification source and one Aidex Bluetooth source may actually be the same physical CGM

The app will still show all of them on the main graph.

You must choose exactly one source as the Primary Output Source.

---

## 7. What the source label means

The app may show each source using information such as:

- vendor name
- transport type
- a display name or origin hint

Examples:

- `Aidex / Notification / default`
- `Aidex / Bluetooth / AB12`
- `Ottai / Notification / phone A`

Use this information to identify the source you want to trust as the primary one.

---

## 8. What happens if the primary source stops updating?

If the selected Primary Output Source does not receive new data for more than **30 minutes**:

- the app will send a notification
- the app will **not** automatically switch to another source
- the app will continue to store and plot other available raw data sources on the main graph

This is intentional.

It keeps the app behavior simple, predictable, and easy to understand.

---

## 9. If you are unsure which source to choose

If you are not sure, use this process:

1. open the main graph
2. compare which source updates more reliably
3. compare which source best reflects the CGM data you want to follow
4. choose that source as the Primary Output Source
5. if needed, change your choice later in settings

You can use the main graph as a comparison view before deciding.

---

## 10. Recommended decision checklist

Before selecting a Primary Output Source, ask yourself:

- Which source updates most consistently?
- Which source disconnects least often?
- Which source do I want alarms to depend on?
- Which source do I want calibration to apply to?
- Which source best represents the CGM I actually want to monitor?

If you can answer these clearly, you can make a better primary-source choice.

---

## 11. Simple summary

### The selected Primary Output Source is used for:

- mini graph
- calibration
- alarms

### All other sources are used for:

- storage
- comparison on the main graph

### If the primary source becomes stale for more than 30 minutes:

- the app sends a notification
- the app does not switch automatically
- the app keeps plotting other raw sources on the main graph

---

## 12. Final advice

Choose the source that is the most reliable for your own real usage.

The main graph helps you compare all available sources.
The mini graph, calibration, and alarms should follow only the one source that you trust most.

That is the meaning of the Primary Output Source in this app.
