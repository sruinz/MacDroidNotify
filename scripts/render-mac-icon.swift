#!/usr/bin/env swift

import AppKit
import Foundation

guard CommandLine.arguments.count == 2 || CommandLine.arguments.count == 3 else {
    fputs("usage: render-mac-icon.swift <output.icns> [AppIcon.appiconset]\n", stderr)
    exit(2)
}

let outputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let appIconSetURL = CommandLine.arguments.count == 3 ? URL(fileURLWithPath: CommandLine.arguments[2]) : nil
let fileManager = FileManager.default
try fileManager.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true)

let iconsetURL = outputURL.deletingPathExtension().appendingPathExtension("iconset")
try? fileManager.removeItem(at: iconsetURL)
try fileManager.createDirectory(at: iconsetURL, withIntermediateDirectories: true)

func color(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(calibratedRed: red / 255, green: green / 255, blue: blue / 255, alpha: alpha)
}

func roundedRect(_ rect: NSRect, _ radius: CGFloat) -> NSBezierPath {
    NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
}

func drawIcon(size: CGFloat) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: Int(size),
        pixelsHigh: Int(size),
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    )!

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high

    let bounds = NSRect(x: 0, y: 0, width: size, height: size)
    let background = roundedRect(bounds.insetBy(dx: size * 0.055, dy: size * 0.055), size * 0.22)
    NSGradient(
        starting: color(10, 18, 28),
        ending: color(20, 118, 128)
    )?.draw(in: background, angle: -38)

    color(255, 255, 255, 0.13).setFill()
    roundedRect(NSRect(x: size * 0.11, y: size * 0.63, width: size * 0.55, height: size * 0.22), size * 0.11).fill()

    let macFrame = NSRect(x: size * 0.14, y: size * 0.34, width: size * 0.52, height: size * 0.36)
    color(235, 248, 250).setFill()
    roundedRect(macFrame, size * 0.055).fill()
    color(12, 18, 26).setFill()
    roundedRect(macFrame.insetBy(dx: size * 0.035, dy: size * 0.04), size * 0.025).fill()
    color(36, 184, 197).setFill()
    roundedRect(NSRect(x: size * 0.23, y: size * 0.52, width: size * 0.25, height: size * 0.045), size * 0.014).fill()
    roundedRect(NSRect(x: size * 0.23, y: size * 0.44, width: size * 0.35, height: size * 0.045), size * 0.014).fill()
    color(235, 248, 250).setFill()
    roundedRect(NSRect(x: size * 0.32, y: size * 0.26, width: size * 0.16, height: size * 0.055), size * 0.014).fill()

    let phoneFrame = NSRect(x: size * 0.58, y: size * 0.18, width: size * 0.25, height: size * 0.62)
    NSGraphicsContext.saveGraphicsState()
    let phoneShadow = NSShadow()
    phoneShadow.shadowColor = color(0, 0, 0, 0.26)
    phoneShadow.shadowOffset = NSSize(width: 0, height: -size * 0.015)
    phoneShadow.shadowBlurRadius = size * 0.035
    phoneShadow.set()
    color(247, 251, 252).setFill()
    roundedRect(phoneFrame, size * 0.07).fill()
    NSGraphicsContext.restoreGraphicsState()

    color(12, 18, 26).setFill()
    roundedRect(phoneFrame.insetBy(dx: size * 0.035, dy: size * 0.055), size * 0.03).fill()
    color(36, 184, 197).setFill()
    roundedRect(NSRect(x: size * 0.66, y: size * 0.54, width: size * 0.10, height: size * 0.045), size * 0.014).fill()
    roundedRect(NSRect(x: size * 0.66, y: size * 0.45, width: size * 0.075, height: size * 0.045), size * 0.014).fill()

    let arc = NSBezierPath()
    arc.move(to: NSPoint(x: size * 0.37, y: size * 0.22))
    arc.curve(
        to: NSPoint(x: size * 0.66, y: size * 0.22),
        controlPoint1: NSPoint(x: size * 0.46, y: size * 0.14),
        controlPoint2: NSPoint(x: size * 0.57, y: size * 0.14)
    )
    color(235, 248, 250, 0.92).setStroke()
    arc.lineWidth = size * 0.035
    arc.lineCapStyle = .round
    arc.stroke()

    NSGraphicsContext.restoreGraphicsState()
    return rep
}

let specs: [(name: String, pixelSize: Int, pointSize: String, scale: String)] = [
    ("icon_16x16.png", 16, "16x16", "1x"),
    ("icon_16x16@2x.png", 32, "16x16", "2x"),
    ("icon_32x32.png", 32, "32x32", "1x"),
    ("icon_32x32@2x.png", 64, "32x32", "2x"),
    ("icon_128x128.png", 128, "128x128", "1x"),
    ("icon_128x128@2x.png", 256, "128x128", "2x"),
    ("icon_256x256.png", 256, "256x256", "1x"),
    ("icon_256x256@2x.png", 512, "256x256", "2x"),
    ("icon_512x512.png", 512, "512x512", "1x"),
    ("icon_512x512@2x.png", 1024, "512x512", "2x"),
]

for spec in specs {
    let rep = drawIcon(size: CGFloat(spec.pixelSize))
    let data = rep.representation(using: .png, properties: [:])!
    try data.write(to: iconsetURL.appendingPathComponent(spec.name))
}

if let appIconSetURL {
    try? fileManager.removeItem(at: appIconSetURL)
    try fileManager.createDirectory(at: appIconSetURL, withIntermediateDirectories: true)
    for spec in specs {
        try fileManager.copyItem(
            at: iconsetURL.appendingPathComponent(spec.name),
            to: appIconSetURL.appendingPathComponent(spec.name)
        )
    }

    let imageEntries = specs.map { spec in
        """
            {
              "filename" : "\(spec.name)",
              "idiom" : "mac",
              "scale" : "\(spec.scale)",
              "size" : "\(spec.pointSize)"
            }
        """
    }.joined(separator: ",\n")
    let contents = """
    {
      "images" : [
    \(imageEntries)
      ],
      "info" : {
        "author" : "xcode",
        "version" : 1
      }
    }
    """
    try contents.write(to: appIconSetURL.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
}

let process = Process()
process.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
process.arguments = ["-c", "icns", iconsetURL.path, "-o", outputURL.path]
try process.run()
process.waitUntilExit()

if process.terminationStatus != 0 {
    fputs("iconutil failed with status \(process.terminationStatus)\n", stderr)
    exit(process.terminationStatus)
}

try? fileManager.removeItem(at: iconsetURL)
