// Draws SVG (or PNG) files into PNGs with AppKit, for seasonal_icons.py (macOS only).
// Usage: swift scripts/icons/rasterize_svg.swift <jobs.tsv>, each line: source, destination, size in pixels, 1 for an
// opaque image (App Store icons have no alpha) or 0 to keep transparency, then optionally the tile's two gradient
// colours (#rrggbb, top to bottom: a vertical gradient keeps the compiled icons small). The tile is filled here, pixel by pixel: AppKit and Core Graphics
// dither gradients, and the noise made every PNG several times larger.
import AppKit
import ImageIO
import UniformTypeIdentifiers

func rgb(_ hex: String) -> [Double] {
    let v = Int(hex.dropFirst(), radix: 16)!
    return [Double((v >> 16) & 0xff), Double((v >> 8) & 0xff), Double(v & 0xff)]
}

let jobs = try! String(contentsOfFile: CommandLine.arguments[1], encoding: .utf8)
for line in jobs.split(separator: "\n") {
    let f = line.split(separator: "\t").map(String.init)
    let (src, dst, size, opaque) = (f[0], f[1], Int(f[2])!, f[3] == "1")
    guard let image = NSImage(contentsOf: URL(fileURLWithPath: src)) else { fatalError("cannot read \(src)") }
    let alpha: CGImageAlphaInfo = opaque ? .noneSkipLast : .premultipliedLast
    let cg = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: alpha.rawValue)!
    if f.count >= 6 {
        let (a, b) = (rgb(f[4]), rgb(f[5]))
        let px = cg.data!.assumingMemoryBound(to: UInt8.self)
        for y in 0..<size {
            for x in 0..<size {
                // memory row 0 is the top of the image
                let t = Double(y) / Double(size - 1)
                let i = (y * size + x) * 4
                for c in 0..<3 { px[i + c] = UInt8((a[c] + (b[c] - a[c]) * t).rounded()) }
                px[i + 3] = 255
            }
        }
    }
    cg.interpolationQuality = .high
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: false)
    image.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
    NSGraphicsContext.restoreGraphicsState()
    try! FileManager.default.createDirectory(atPath: (dst as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    let out = CGImageDestinationCreateWithURL(URL(fileURLWithPath: dst) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(out, cg.makeImage()!, nil)
    guard CGImageDestinationFinalize(out) else { fatalError("cannot write \(dst)") }
}
