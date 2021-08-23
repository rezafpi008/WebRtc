# WebRtc

This project uses the WebRtc library to communicate between two users.
# How to run project

**1. Clone the project in your system**

**2. Enter the Firebase panel and create a new application, in the Package name field, enter the project package name**
>**(com.texon.example.webrtc)**
>
**3. In the SHA certificate fingerprints section, you must put your sha key. To get the sha key, you can use the following codes in the command line**
>**keytool -alias androiddebugkey -keystore "C: \ Users \ ... \. android \ debug.keystore" -list -v** (debug)
>
**4. After registering your application, download the json file and put it in the specified part of your application**
**5. In the Firebase panel, enter the firestore database section. Enter the Rules tab and change the read and write file to the opposite in the access section.**
>**allow read, write: if true;**

**6.Build the project on two devices**

**7. Enter your room name on the first device and click start**

**8. In the second device, enter the name of the room you entered in the first device and click join**

**If you want to use your own stun server in the project, you can put your own server Url in the RTCClient class instead of the existing server.**
>**stun:stun.l.google.com:19302**

#  Built With
## [Firebase](https://firebase.google.com/)

## [WebRtc](https://webrtc.github.io/)
