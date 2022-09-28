FROM centos:7

COPY install-deps /tmp/install-deps
RUN /tmp/install-deps

RUN echo "mysql ALL=(ALL:ALL) NOPASSWD:ALL" >> /etc/sudoers \
    && groupadd -g 1001 -o -r mysql \
    && useradd -M -N -g mysql -o -r -d /var/lib/mysql -s /bin/false -c "Percona Server" -u 1001 mysql
USER mysql
