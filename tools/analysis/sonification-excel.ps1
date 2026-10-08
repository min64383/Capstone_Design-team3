param([Parameter(Mandatory=$true)][string]$RunDir)
$ErrorActionPreference = 'Stop'
$dir = (Resolve-Path $RunDir).Path
$csvPath = Join-Path $dir 'sonification.csv'
$target = Join-Path $dir 'sonification.xlsx'
if (Test-Path $target) { throw "Output already exists: $target" }
$rows = @(Import-Csv $csvPath)
if ($rows.Count -eq 0) { throw 'CSV is empty' }
$excel = $null
$book = $null
$culture = [Globalization.CultureInfo]::InvariantCulture
try {
    # Windows desktop Excel required; no Python packages required.
    $excel = New-Object -ComObject Excel.Application
    $excel.Visible = $false
    $excel.DisplayAlerts = $false
    $book = $excel.Workbooks.Add()
    $info = $book.Worksheets.Item(1)
    $info.Name = 'Readme'
    $notes = @('X axis = elapsedS (WAV seconds).', 'pitch/gain = end of block, before HRTF/master/limiter.',
        'Blank TTC = no finite TTC. It does not mean zero.', 'NO_SOURCE does not mean safe or no obstacle.',
        'outputRms/outputPeak = whole stereo mix, repeated on source rows.',
        'Charts use full data, one sheet per obstacle. WAV: audio.wav',
        'active may be false during audible release. Duck is STOP priority, not TTS.')
    for ($i=0; $i -lt $notes.Count; $i++) { $info.Cells.Item($i+1,1) = $notes[$i] }
    $info.Columns.Item(1).ColumnWidth = 95
    $fields = @('elapsedS','distanceM','closingMps','ttcS','risk','active','targetPitchHz','pitchHz','targetGain','gain','duckGain','ducked','outputRms','outputPeak','heightDeltaM')
    $timeline = @($rows | Group-Object frameStart | ForEach-Object { $_.Group[0] })
    $groups = @($rows | Where-Object { $_.obstacleId -ne '' } | Group-Object obstacleId)
    # Always include a mix sheet, even for all-UNKNOWN runs.
    $groups += [pscustomobject]@{Name='mix'; Group=@($rows | Group-Object frameStart | ForEach-Object { $_.Group[0] })}
    foreach ($group in $groups) {
        $sheet = $book.Worksheets.Add()
        $sheet.Name = 'source_' + $group.Name
        $dataRows = @($group.Group)
        if ($group.Name -ne 'mix') {
            $byFrame = @{}
            foreach ($item in $dataRows) { $byFrame[$item.frameStart] = $item }
            $dataRows = @($timeline | ForEach-Object {
                if ($byFrame.ContainsKey($_.frameStart)) { $byFrame[$_.frameStart] }
                else {
                    $empty = @{}
                    foreach ($field in $fields) { $empty[$field] = '' }
                    $empty['elapsedS'] = $_.elapsedS
                    [pscustomobject]$empty
                }
            })
        }
        if ($dataRows.Count + 1 -gt 1048576) { throw 'Too many rows for Excel. Use a shorter session.' }
        $data = New-Object 'object[,]' ($dataRows.Count+1),$fields.Count
        for ($col=0; $col -lt $fields.Count; $col++) { $data[0,$col] = $fields[$col] }
        for ($row=0; $row -lt $dataRows.Count; $row++) {
            for ($col=0; $col -lt $fields.Count; $col++) {
                $v = $dataRows[$row].($fields[$col])
                if ($v -eq 'true') { $data[($row+1),$col] = 1.0 }
                elseif ($v -eq 'false') { $data[($row+1),$col] = 0.0 }
                elseif ($v -ne '') { $data[($row+1),$col] = [double]::Parse($v,$culture) }
            }
        }
        $last = $dataRows.Count+1
        $sheet.Range($sheet.Cells.Item(1,1),$sheet.Cells.Item($last,$fields.Count)).Value2 = $data
        $sheet.Rows.Item(1).Font.Bold = $true
        $sheet.Columns.AutoFit() | Out-Null
        $sets = if ($group.Name -eq 'mix') { @('13,14') } else { @('2','3','4','5','15','7,8','9,10','6,11,12') }
        $index = 0
        foreach ($set in $sets) {
            $chart = $sheet.ChartObjects().Add(1050,20+260*$index,640,245).Chart
            $chart.ChartType = 74 # xlXYScatterLinesNoMarkers: numeric time axis
            $chart.DisplayBlanksAs = 1 # xlNotPlotted
            $names = @()
            foreach ($column in $set.Split(',')) {
                $c = [int]$column
                $series = $chart.SeriesCollection().NewSeries()
                $series.Name = $fields[$c-1]
                $series.XValues = $sheet.Range($sheet.Cells.Item(2,1),$sheet.Cells.Item($last,1))
                $series.Values = $sheet.Range($sheet.Cells.Item(2,$c),$sheet.Cells.Item($last,$c))
                $names += $fields[$c-1]
            }
            $chart.HasTitle = $true
            $chart.ChartTitle.Text = $names -join ' / '
            $chart.Axes(1).HasTitle = $true
            $chart.Axes(1).AxisTitle.Text = 'WAV time (seconds)'
            $index++
        }
    }
    $book.SaveAs($target,51) # xlsx
    Write-Host "Saved: $target"
} finally {
    if ($null -ne $book) { $book.Close($false) }
    if ($null -ne $excel) {
        $excel.Quit()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($excel)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
