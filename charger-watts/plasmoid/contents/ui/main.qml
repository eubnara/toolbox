/*
 * Charger Watts — Plasma 6 panel widget.
 *
 * Pure event-driven, zero polling. The Plasma "powermanagement" data engine
 * is backed by Solid -> UPower D-Bus PropertiesChanged signals, so it pushes
 * an update only when the AC adapter is actually plugged or unplugged.
 *
 * On each plug edge we run /usr/local/bin/charger-watts --raw to get the
 * negotiated wattage. Click the widget to force a re-read (useful if the
 * PD profile renegotiates without an unplug).
 */
import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.components 3.0 as PlasmaComponents
import org.kde.plasma.plasma5support 2.0 as P5Support
import org.kde.kirigami 2 as Kirigami

PlasmoidItem {
    id: root

    property string wattsText: "—"
    property bool connected: false
    property int lastPlugState: -1   // -1 unknown, 0 off, 1 on

    readonly property string wattsCmd: "/usr/local/bin/charger-watts --raw"

    P5Support.DataSource {
        id: pm
        engine: "powermanagement"
        connectedSources: ["AC Adapter"]

        onDataChanged: {
            const ac = data["AC Adapter"]
            if (!ac) return
            const cur = ac["Plugged in"] === true ? 1 : 0
            if (cur === root.lastPlugState) return
            root.lastPlugState = cur
            if (cur === 1) {
                runner.exec(root.wattsCmd)
            } else {
                root.connected = false
                root.wattsText = "—"
            }
        }
    }

    P5Support.DataSource {
        id: runner
        engine: "executable"
        connectedSources: []

        onNewData: function(sourceName, data) {
            disconnectSource(sourceName)
            const out = (data["stdout"] || "").trim()
            if (out === "0" || out === "") {
                root.connected = false
                root.wattsText = "—"
            } else {
                root.connected = true
                root.wattsText = out + "W"
            }
        }

        function exec(c) {
            if (connectedSources.indexOf(c) === -1) connectSource(c)
        }
    }

    preferredRepresentation: compactRepresentation

    compactRepresentation: Item {
        Layout.minimumWidth:   label.implicitWidth + Kirigami.Units.smallSpacing * 2
        Layout.preferredWidth: Layout.minimumWidth

        PlasmaComponents.Label {
            id: label
            anchors.centerIn: parent
            text: root.wattsText
            font.pixelSize: Kirigami.Units.gridUnit * 0.85
            opacity: root.connected ? 1.0 : 0.5
        }

        MouseArea {
            anchors.fill: parent
            onClicked: runner.exec(root.wattsCmd)
        }
    }

    fullRepresentation: ColumnLayout {
        Layout.preferredWidth:  Kirigami.Units.gridUnit * 10
        Layout.preferredHeight: Kirigami.Units.gridUnit * 4
        spacing: Kirigami.Units.smallSpacing

        PlasmaComponents.Label {
            Layout.alignment: Qt.AlignHCenter
            text: root.connected ? "Charger" : "No charger"
            opacity: 0.7
        }
        PlasmaComponents.Label {
            Layout.alignment: Qt.AlignHCenter
            text: root.wattsText
            font.pixelSize: Kirigami.Units.gridUnit * 1.6
        }
    }
}
