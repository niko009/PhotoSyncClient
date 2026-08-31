FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY server/PhotoSync.Server/PhotoSync.Server.csproj ./
RUN dotnet restore PhotoSync.Server.csproj
COPY server/PhotoSync.Server/ ./
RUN dotnet publish PhotoSync.Server.csproj -c Release --no-restore -o /out /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS final
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8080 \
    ASPNETCORE_ENVIRONMENT=Production \
    PhotoSync__StorageRoot=/data \
    PhotoSync__DatabasePath=/data/system/photosync.db \
    ConnectionStrings__PhotoSync="Data Source=/data/system/photosync.db"
# A new named volume inherits these directories and their non-root ownership.
RUN mkdir -p /data/system /data/_temp && chown -R "$APP_UID:$APP_UID" /data
COPY --from=build /out ./
USER $APP_UID
EXPOSE 8080
ENTRYPOINT ["dotnet", "PhotoSync.Server.dll"]
