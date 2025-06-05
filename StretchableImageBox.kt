@Composable
fun StretchableImageBox(
    image: ImageBitmap,
    inputText: String,
    modifier: Modifier = Modifier,
    minStretchHeight: Dp = 40.dp,
    maxStretchHeight: Dp = 200.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val imgHeight = image.height
    val imgWidth = image.width

    val topHeightPx = (imgHeight * 0.1f).toInt()
    val middleHeightPx = (imgHeight * 0.1f).toInt()
    val bottomHeightPx = imgHeight - topHeightPx - middleHeightPx

    // Debounced height (based on input text length for example)
    var debouncedText by remember { mutableStateOf(inputText) }
    LaunchedEffect(inputText) {
        delay(300) // debounce
        debouncedText = inputText
    }

    // 动态高度计算（示例按字符数决定拉伸高度）
    val targetStretchHeight = remember(debouncedText) {
        val lineCount = max(1, debouncedText.length / 10)
        (minStretchHeight.value + lineCount * 20).coerceAtMost(maxStretchHeight.value)
    }.dp

    val animatedStretchHeight by animateDpAsState(targetValue = targetStretchHeight)

    // 计算各区域高度
    val topRatio = topHeightPx.toFloat() / imgHeight
    val middleRatio = middleHeightPx.toFloat() / imgHeight
    val bottomRatio = bottomHeightPx.toFloat() / imgHeight

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val topCanvasHeight = canvasHeight * topRatio
        val stretchCanvasHeight = with(density) { animatedStretchHeight.toPx() }
        val bottomCanvasHeight = canvasHeight - topCanvasHeight - stretchCanvasHeight

        // 上部
        drawImage(
            image = image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(imgWidth, topHeightPx),
            dstOffset = Offset(0f, 0f),
            dstSize = IntSize(canvasWidth.toInt(), topCanvasHeight.toInt())
        )

        // 中部（拉伸）
        drawImage(
            image = image,
            srcOffset = IntOffset(0, topHeightPx),
            srcSize = IntSize(imgWidth, middleHeightPx),
            dstOffset = Offset(0f, topCanvasHeight),
            dstSize = IntSize(canvasWidth.toInt(), stretchCanvasHeight.toInt())
        )

        // 下部
        drawImage(
            image = image,
            srcOffset = IntOffset(0, topHeightPx + middleHeightPx),
            srcSize = IntSize(imgWidth, bottomHeightPx),
            dstOffset = Offset(0f, topCanvasHeight + stretchCanvasHeight),
            dstSize = IntSize(canvasWidth.toInt(), bottomCanvasHeight.toInt())
        )
    }

    // 内容层叠在中间拉伸区域上
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .offset(y = with(density) { (topRatio * size.height).toDp() })
                .height(animatedStretchHeight)
                .fillMaxWidth(),
            content = content
        )
    }
}
