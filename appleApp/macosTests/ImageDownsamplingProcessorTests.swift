import AppKit
import FlareAppleUI
import ImageIO
import Kingfisher
import UniformTypeIdentifiers
import XCTest

final class ImageDownsamplingProcessorTests: XCTestCase {
    func testAnimatedDraftUsesBoundedFirstFrame() throws {
        let data = try imageData(type: .gif, frames: 2)
        let processor = ImageDownsamplingProcessor(
            pixelSize: CGSize(width: 120, height: 120), fillsBounds: true, preservesAnimation: false
        )
        let image = try XCTUnwrap(processor.process(item: .data(data), options: .init([.scaleFactor(2)])))
        let pixels = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        XCTAssertEqual(pixels.width, 180)
        XCTAssertEqual(pixels.height, 120)
        XCTAssertLessThanOrEqual(image.kf.imageFrameCount ?? 1, 1)
    }

    func testStaticAspectFillStillUsesRequestedPixels() throws {
        let processor = ImageDownsamplingProcessor(pixelSize: CGSize(width: 120, height: 120), fillsBounds: true)
        let image = try XCTUnwrap(processor.process(item: .data(try imageData(type: .png, frames: 1)),
                                                  options: .init([.scaleFactor(2)])))
        let pixels = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        XCTAssertEqual(pixels.width, 180)
        XCTAssertEqual(pixels.height, 120)
    }

    func testRotatedThumbnailUsesDisplayScaleAndOrientedBounds() throws {
        let processor = ImageDownsamplingProcessor(
            pixelSize: CGSize(width: 180, height: 180), fillsBounds: true, preservesAnimation: false
        )
        let image = try XCTUnwrap(processor.process(item: .data(try imageData(type: .jpeg, frames: 1, orientation: 6)),
                                                  options: .init([.scaleFactor(3)])))
        let pixels = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        XCTAssertEqual(pixels.width, 180)
        XCTAssertEqual(pixels.height, 270)
    }

    func testStaticThumbnailDoesNotCollideWithAnimationCache() throws {
        let size = CGSize(width: 120, height: 120)
        let normal = ImageDownsamplingProcessor(pixelSize: size, fillsBounds: true)
        let thumbnail = ImageDownsamplingProcessor(pixelSize: size, fillsBounds: true, preservesAnimation: false)
        XCTAssertNotEqual(normal.identifier, thumbnail.identifier)
        let image = try XCTUnwrap(normal.process(item: .data(try imageData(type: .gif, frames: 2)), options: .init([])))
        let pixels = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
        XCTAssertEqual(pixels.width, 1200)
        XCTAssertEqual(pixels.height, 800)
    }

    private func imageData(type: UTType, frames: Int, orientation: Int = 1) throws -> Data {
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(data, type.identifier as CFString, frames, nil))
        let context = try XCTUnwrap(CGContext(data: nil, width: 1200, height: 800, bitsPerComponent: 8, bytesPerRow: 0,
                                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        for index in 0..<frames {
            context.setFillColor(CGColor(red: index == 0 ? 1 : 0, green: 0, blue: index == 0 ? 0 : 1, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: 1200, height: 800))
            CGImageDestinationAddImage(destination, try XCTUnwrap(context.makeImage()),
                                      [kCGImagePropertyOrientation: orientation] as CFDictionary)
        }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
}
