import Foundation
import CoreLocation
import UIKit
import Capacitor

@objc(GeolocationPlugin)
public class GeolocationPlugin: CAPPlugin, CLLocationManagerDelegate {

    enum CallType {
        case permissions
        case singleUpdate
        case watch
    }

    var locationManager = CLLocationManager()
    private var isUpdatingLocation: Bool = false
    private var callQueue: [String: CallType] = [:]

    @objc func getCurrentPosition(_ call: CAPPluginCall) {
        bridge?.saveCall(call)
        callQueue[call.callbackId] = .singleUpdate

        DispatchQueue.main.async {
            self.locationManager.delegate = self

            if call.getBool("enableHighAccuracy", false) == true {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
            } else {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
            }

            if CLLocationManager.authorizationStatus() == .notDetermined {
                self.locationManager.requestWhenInUseAuthorization()
            } else {
                self.locationManager.requestLocation()
            }
        }
    }

    @objc func watchPosition(_ call: CAPPluginCall) {
        call.keepAlive = true
        callQueue[call.callbackId] = .watch

        DispatchQueue.main.async {
            self.locationManager.delegate = self

            if call.getBool("enableHighAccuracy", false) == true {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
                self.locationManager.activityType = .automotiveNavigation
            } else {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
                self.locationManager.activityType = .other
            }

            let minimumUpdateDistance = call.getDouble("minimumUpdateDistance", 0)
            self.locationManager.distanceFilter = minimumUpdateDistance > 0
                ? minimumUpdateDistance
                : kCLDistanceFilterNone
            let background = call.getBool("background", false)
            self.locationManager.allowsBackgroundLocationUpdates = background
            self.locationManager.showsBackgroundLocationIndicator = background
            self.locationManager.pausesLocationUpdatesAutomatically = !background

            if CLLocationManager.authorizationStatus() == .notDetermined {
                if background {
                    self.locationManager.requestAlwaysAuthorization()
                } else {
                    self.locationManager.requestWhenInUseAuthorization()
                }
            } else if background && CLLocationManager.authorizationStatus() == .authorizedWhenInUse {
                self.locationManager.requestAlwaysAuthorization()
                self.locationManager.startUpdatingLocation()
                self.isUpdatingLocation = true
            } else {
                self.locationManager.startUpdatingLocation()
                self.isUpdatingLocation = true
            }
        }
    }

    @objc func clearWatch(_ call: CAPPluginCall) {
        guard let callbackId = call.getString("id") else {
            call.reject("Watch call id must be provided")
            return
        }

        if let savedCall = bridge?.savedCall(withID: callbackId) {
            bridge?.releaseCall(savedCall)
        }

        callQueue.removeValue(forKey: callbackId)

        if callQueue.values.allSatisfy({ $0 != .watch }) {
            self.stopUpdating()
            self.locationManager.allowsBackgroundLocationUpdates = false
            self.locationManager.showsBackgroundLocationIndicator = false
            self.locationManager.pausesLocationUpdatesAutomatically = true
        }

        call.resolve()
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let removalQueue = callQueue.filter { $0.value == .permissions || $0.value == .singleUpdate }
        callQueue = callQueue.filter { $0.value == .watch }

        for (id, _) in removalQueue {
            if let call = bridge?.savedCall(withID: id) {
                call.reject(error.localizedDescription)
                bridge?.releaseCall(call)
            }
        }

        for (id, _) in callQueue {
            if let call = bridge?.savedCall(withID: id) {
                call.reject(error.localizedDescription)
            }
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let removalQueue = callQueue.filter { $0.value == .singleUpdate }
        callQueue = callQueue.filter { $0.value != .singleUpdate }

        for (id, _) in removalQueue {
            if let call = bridge?.savedCall(withID: id) {
                reportLocation(call, locations)
                bridge?.releaseCall(call)
            }
        }

        for (id, callType) in callQueue {
            if let call = bridge?.savedCall(withID: id), callType == .watch {
                reportLocation(call, locations)
            }
        }
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        let removalQueue = callQueue.filter { $0.value == .permissions }
        callQueue = callQueue.filter { $0.value != .permissions }

        for (id, _) in removalQueue {
            if let call = bridge?.savedCall(withID: id) {
                checkPermissions(call)
                bridge?.releaseCall(call)
            }
        }

        if !(callQueue.filter({ $0.value == .singleUpdate }).isEmpty) {
            self.locationManager.requestLocation()
        }

        if !(callQueue.filter({ $0.value == .watch }).isEmpty) && !self.isUpdatingLocation {
            self.locationManager.startUpdatingLocation()
            self.isUpdatingLocation = true
        }
    }

    public func stopUpdating() {
        self.locationManager.stopUpdatingLocation()
        self.isUpdatingLocation = false
    }

    private func reportLocation(_ call: CAPPluginCall, _ locations: [CLLocation]) {
        if let location = locations.last {
            let result = makePosition(location)
            call.resolve(result)
        } else {
            // TODO: Handle case where location is nil
            call.resolve()
        }
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        var status: String = ""

        if CLLocationManager.locationServicesEnabled() {
            switch CLLocationManager.authorizationStatus() {
            case .notDetermined:
                status = "prompt"
            case .restricted, .denied:
                status = "denied"
            case .authorizedAlways, .authorizedWhenInUse:
                status = "granted"
            @unknown default:
                status = "prompt"
            }
        } else {
            call.reject("Location services are not enabled")
            return
        }

        let result = [
            "location": status,
            "coarseLocation": status
        ]

        call.resolve(result)
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        if CLLocationManager.locationServicesEnabled() {
            // If state is not yet determined, request perms.
            // Otherwise, report back the state right away
            if CLLocationManager.authorizationStatus() == .notDetermined {
                bridge?.saveCall(call)
                callQueue[call.callbackId] = .permissions

                DispatchQueue.main.async {
                    self.locationManager.delegate = self
                    self.locationManager.requestWhenInUseAuthorization()
                }
            } else {
                checkPermissions(call)
            }
        } else {
            call.reject("Location services are not enabled")
        }
    }

    func makePosition(_ location: CLLocation) -> JSObject {
        var ret = JSObject()
        var coords = JSObject()
        coords["latitude"] = location.coordinate.latitude
        coords["longitude"] = location.coordinate.longitude
        coords["accuracy"] = location.horizontalAccuracy
        coords["altitude"] = location.altitude
        coords["altitudeAccuracy"] = location.verticalAccuracy
        coords["speed"] = location.speed
        coords["heading"] = location.course
        ret["timestamp"] = Int((location.timestamp.timeIntervalSince1970 * 1000))
        ret["coords"] = coords
        return ret
    }

}
