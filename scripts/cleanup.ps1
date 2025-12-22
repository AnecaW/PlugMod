#!/usr/bin/env pwsh
# Cleanup workspace: remove IDE and build artifacts (safe, non-fatal)
Write-Output "Running cleanup..."
Try {
    if (Test-Path -LiteralPath ".idea") {
        Remove-Item -LiteralPath ".idea" -Recurse -Force -ErrorAction SilentlyContinue
        Write-Output ".idea removed"
    } else {
        Write-Output ".idea not found"
    }

    if (Test-Path -LiteralPath "target") {
        Remove-Item -LiteralPath "target" -Recurse -Force -ErrorAction SilentlyContinue
        Write-Output "target removed"
    } else {
        Write-Output "target not found"
    }

    Get-ChildItem -Path . -Filter "*.iml" -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
        Write-Output "Removed $($_.FullName)"
    }

    Write-Output "Cleanup finished. Consider committing the .gitignore." 
} Catch {
    Write-Error "Cleanup encountered an error: $_"
}
